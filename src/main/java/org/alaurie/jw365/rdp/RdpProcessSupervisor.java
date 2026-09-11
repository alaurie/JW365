package org.alaurie.jw365.rdp;

import org.alaurie.jw365.config.XdgPaths;
import org.alaurie.jw365.feed.WorkspaceResource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Supervises the lifecycle, execution, and output logging of FreeRDP processes.
 */
public final class RdpProcessSupervisor {
    private static final long MAX_SESSION_LOG_BYTES = 2L * 1024 * 1024;
    private static final String REDACTED = "[REDACTED]";


    private final Map<String, ActiveSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private static final Map<Path, Object> LOG_LOCKS = new ConcurrentHashMap<>();
    private final List<SessionListener> globalListeners = new CopyOnWriteArrayList<>();

    public RdpProcessSupervisor() {
    }

    public void addGlobalListener(SessionListener listener) {
        if (listener != null) {
            globalListeners.add(listener);
        }
    }

    public void removeGlobalListener(SessionListener listener) {
        if (listener != null) {
            globalListeners.remove(listener);
        }
    }

    /**
     * Builds the command line argument list for launching FreeRDP with AVD / AAD parameters.
     */
    public static List<String> buildCommandLine(
        FreeRdpInfo freeRdp,
        RdpSessionConfig config
    ) {
        List<String> cmd = new ArrayList<>();

        if (freeRdp.isFlatpak()) {
            cmd.add("flatpak");
            cmd.add("run");
            cmd.add("--file-forwarding");
            cmd.add(freeRdp.flatpakAppId() != null ? freeRdp.flatpakAppId() : "com.freerdp.FreeRDP");
            cmd.add("@@");
            cmd.add(config.rdpFile().toAbsolutePath().toString());
            cmd.add("@@");
        } else if (freeRdp.binaryPath() != null) {
            cmd.add(freeRdp.binaryPath().toString());
            cmd.add(config.rdpFile().toAbsolutePath().toString());
        } else {
            cmd.add("sdl-freerdp3");
            cmd.add(config.rdpFile().toAbsolutePath().toString());
        }
        // AVD Gateway and Entra ID (AAD) authentication flags
        cmd.add("/gateway:type:arm");
        cmd.add("/sec:aad");

        if (config.username() != null && !config.username().isBlank()) {
            cmd.add("/u:" + config.username());
        }

        // PipeWire / PulseAudio native 48kHz stereo output and input
        if (config.sound()) {
            cmd.add("/sound:sys:pulse,rate:48000,channel:2,quality:high");
        }
        if (config.microphone()) {
            cmd.add("/microphone:sys:pulse,rate:48000");
        }

        // Peripheral redirection
        if (config.usbRedirection()) {
            cmd.add("/usb:auto");
        }
        if (config.smartcard()) {
            cmd.add("/smartcard");
        }
        // Display settings
        if (config.multiMonitor()) {
            cmd.add("/f");
            cmd.add("/multimon:force");
        } else if (config.fullscreen()) {
            cmd.add("/f");
        }
        if (config.scalePercent() > 0 && !config.fullscreen() && !config.multiMonitor()) {
            cmd.add("/scale-desktop:" + config.scalePercent());
        }
        if (config.ignoreCert()) {
            cmd.add("/cert:ignore");
        }

        // Clipboard synchronization
        if (config.clipboard()) {
            cmd.add("+clipboard");
        }

        // Dynamic desktop resolution updates
        if (config.dynamicResolution() && !config.fullscreen() && !config.multiMonitor()) {
            cmd.add("+dynamic-resolution");
        }

        // Network auto-optimization and compression
        cmd.add("/network:auto");
        cmd.add("+compression");

        // Font smoothing (ClearType) and desktop composition
        cmd.add("+fonts");
        cmd.add("+aero");

        // Asynchronous channel processing (FreeRDP 3 deactivated +async-update due to race conditions / stalls)
        if (config.asyncUpdate()) {
            cmd.add("+async-channels");
        }

        // Automatic reconnection on network blips
        if (config.autoReconnect()) {
            cmd.add("+auto-reconnect");
            cmd.add("/auto-reconnect-max-retries:10");
        }

        // H.264 / AVC420 and Progressive graphics pipeline with RemoteFX
        if (config.gfxProgressive()) {
            cmd.add("/gfx:AVC420,progressive");
            cmd.add("+rfx");
        }

        // Display color depth and software GDI renderer.
        // GDI software rendering draws into a software surface which the GFX channel composites
        // directly into the SDL OpenGL texture, eliminating buffer-swapping flicker caused by /gdi:hw.
        cmd.add("/gdi:sw");
        cmd.add("/bpp:32");
        // Log level
        cmd.add("/log-level:info");
        // Custom extra arguments
        if (config.extraArgs() != null) {
            for (String arg : config.extraArgs()) {
                if (arg == null || arg.indexOf('\u0000') >= 0 || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("FreeRDP arguments cannot contain control characters");
                }
            }
            cmd.addAll(config.extraArgs());
        }

        return cmd;
    }

    /**
     * Launches a FreeRDP session for a workspace resource.
     *
     * @param freeRdp  FreeRDP installation info
     * @param resource target workspace resource
     * @param config   session parameters
     * @param listener optional session-specific event listener
     */
    public void launch(
        FreeRdpInfo freeRdp,
        WorkspaceResource resource,
        RdpSessionConfig config,
        SessionListener listener
    ) throws IOException {
        Objects.requireNonNull(freeRdp, "freeRdp must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(config, "config must not be null");
        String sessionId = resource.identityKey();
        Object sessionLock = sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
        Path logFile;
        Process process;
        ActiveSession session;
        synchronized (sessionLock) {
            ActiveSession existing = activeSessions.get(sessionId);
            if (existing != null) existing.stop();
            stopSession(sessionId);

        List<String> rawCommand = buildCommandLine(freeRdp, config);
        if (config.multiMonitor() && !freeRdp.isFlatpak()) {
            detectMonitorSelection(freeRdp).ifPresent(selection -> rawCommand.add("/monitors:" + selection));
        } else if (config.fullscreen() && freeRdp.isFlatpak()) {
            detectMonitorSelection(freeRdp).map(selection -> selection.split(",")[0])
                .ifPresent(primary -> rawCommand.add("/monitors:" + primary));
        }
        // Use script PTY wrapper if available to provide a valid terminal for FreeRDP
        List<String> processCommand;
        if (Files.isExecutable(Path.of("/usr/bin/script"))) {
            String joined = rawCommand.stream()
                .map(arg -> "'" + arg.replace("'", "'\\''") + "'")
                .collect(java.util.stream.Collectors.joining(" "));
            processCommand = List.of("/usr/bin/script", "-q", "-c", joined, "/dev/null");
        } else {
            processCommand = rawCommand;
        }

        logFile = XdgPaths.logsDir().resolve("session_" + resource.sanitizedFileName() + "_" + System.currentTimeMillis() + "_" + java.util.UUID.randomUUID() + ".log");
        ProcessBuilder pb = new ProcessBuilder(processCommand);
        pb.redirectErrorStream(true);
        Path logDir = logFile.getParent();
        if (logDir != null) {
            Files.createDirectories(logDir);
            try { Files.setPosixFilePermissions(logDir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); } catch (Exception ignored) { }
        }
        if (!Files.exists(logFile)) {
            Files.createFile(logFile);
        }
        try { Files.setPosixFilePermissions(logFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (Exception ignored) { }
        Map<String, String> env = pb.environment();
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        String display = System.getenv("DISPLAY");
        if (display != null && !display.isBlank()) {
            env.put("DISPLAY", display);
        }
        if (waylandDisplay != null && !waylandDisplay.isBlank()) {
            env.put("WAYLAND_DISPLAY", waylandDisplay);
            boolean isFlatpakEnv = freeRdp.isFlatpak() || System.getenv("FLATPAK_ID") != null;
            if (display != null && !display.isBlank()) {
                // In Flatpak or when XWayland is available, SDL3 client on X11 avoids the Wayland buffer-swap
                // tearing and double-buffering flickering anomalies.
                env.put("SDL_VIDEODRIVER", isFlatpakEnv ? "x11" : "wayland,x11");
            } else {
                env.put("SDL_VIDEODRIVER", "wayland,x11");
            }
        }
        String xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
        if (xdgRuntime != null && !xdgRuntime.isBlank()) {
            env.put("XDG_RUNTIME_DIR", xdgRuntime);
        }
        // Synchronize SDL presentation with vertical refresh rate and force immediate double buffering
        // to eliminate tearing, buffer swapping flickering, and presentation lag.
        env.put("SDL_RENDER_VSYNC", "1");
        env.put("SDL_VIDEO_DOUBLE_BUFFER", "1");


        // PipeWire / PulseAudio direct native socket path
        String pulseServer = System.getenv("PULSE_SERVER");
        if (pulseServer != null && !pulseServer.isBlank()) {
            env.put("PULSE_SERVER", pulseServer);
        } else if (xdgRuntime != null && !xdgRuntime.isBlank() && Files.exists(Path.of(xdgRuntime, "pulse", "native"))) {
            env.put("PULSE_SERVER", "unix:" + xdgRuntime + "/pulse/native");
        }
        process = pb.start();

        session = new ActiveSession(
            sessionId,
            resource.title(),
            process,
            SessionStatus.STARTING
        );

        activeSessions.put(sessionId, session);
        }

        // Notify started
        emitEvent(listener, new SessionEvent.Started(sessionId, process.toHandle()));
        updateStatus(session, listener, SessionStatus.CONNECTING, "Connecting to " + resource.title() + "...");

        // Start Virtual Thread to monitor output and lifecycle
        Thread.ofVirtual().name("rdp-watcher-" + resource.sanitizedFileName()).start(() -> {
            Object logLock = LOG_LOCKS.computeIfAbsent(logFile.toAbsolutePath(), ignored -> new Object());
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter logWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                long[] logBytes;
                synchronized (logLock) {
                    logBytes = new long[] {Files.size(logFile)};
                }
                writeLogLine(logWriter, logBytes, "=== JW365 Session Log for " + resource.title() + " ===", logLock);
                writeLogLine(logWriter, logBytes, "Started: " + Instant.now(), logLock);
                writeLogLine(logWriter, logBytes, "", logLock);

                String line;
                while ((line = reader.readLine()) != null) {
                    writeLogLine(logWriter, logBytes, redactLogLine(line), logLock);
                    emitEvent(listener, new SessionEvent.OutputLine(sessionId, line, false));

                    if (line.contains("Browse to: ")) {
                        String rawAuth = line.substring(line.indexOf("Browse to: ") + "Browse to: ".length()).trim();
                        String authUrl = rawAuth.replaceAll("\u001B\\[[;?0-9]*[a-zA-Z]", "").trim();
                        int spaceIdx = authUrl.indexOf(' ');
                        if (spaceIdx > 0) {
                            authUrl = authUrl.substring(0, spaceIdx).trim();
                        }
                        updateStatus(session, listener, SessionStatus.CONNECTING, "Authenticating Cloud PC session...");
                        final String cleanAuthUrl = authUrl;
                        emitEvent(listener, new SessionEvent.AuthRequired(sessionId, cleanAuthUrl, redirectUrl -> {
                            String toSend = redirectUrl != null ? redirectUrl.trim() : "";
                            session.writeInput(toSend + "\n");
                        }));
                    }

                    String lower = line.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("reconnect") || lower.contains("reconnecting")) {
                        updateStatus(session, listener, SessionStatus.RECONNECTING, "Reconnecting...");
                    }
                    if (isConnectedMarker(line) && session.status() != SessionStatus.CONNECTED) {
                        updateStatus(session, listener, SessionStatus.CONNECTED, "Connected");
                    }
                }
                int exitCode = process.waitFor();
                writeLogLine(logWriter, logBytes, "", logLock);
                writeLogLine(logWriter, logBytes, "=== Process exited with code " + exitCode + " at " + Instant.now() + " ===", logLock);

                if (session.isUserInitiatedStop() || exitCode == 0 || exitCode == 143 || exitCode == 130 || exitCode == 129) {
                    updateStatus(session, listener, SessionStatus.DISCONNECTED, "Session disconnected");
                } else {
                    updateStatus(session, listener, SessionStatus.FAILED, "Session exited with error code " + exitCode);
                }
                emitEvent(listener, new SessionEvent.Exited(sessionId, exitCode, session.isUserInitiatedStop() ? "Session disconnected" : "Session exited with error code " + exitCode));
            } catch (InterruptedException e) {
                destroyAndAwait(process);
                Thread.currentThread().interrupt();
                updateStatus(session, listener, SessionStatus.DISCONNECTED, "Session monitoring interrupted");
                emitEvent(listener, new SessionEvent.Exited(sessionId, -1, "Session monitoring interrupted"));
            } catch (Exception e) {
                destroyAndAwait(process);
                if (session.isUserInitiatedStop()) {
                    updateStatus(session, listener, SessionStatus.DISCONNECTED, "Session disconnected");
                    emitEvent(listener, new SessionEvent.Exited(sessionId, 0, "Session disconnected"));
                } else {
                    updateStatus(session, listener, SessionStatus.FAILED, "Session monitoring error: " + e.getMessage());
                    emitEvent(listener, new SessionEvent.Exited(sessionId, -1, "Session monitoring error: " + e.getMessage()));
                }
            } finally {
                LOG_LOCKS.remove(logFile.toAbsolutePath(), logLock);
                XdgPaths.pruneOldLogs(10);
                activeSessions.remove(sessionId, session);
            }
        });
    }

    private static void destroyAndAwait(Process process) {
        if (!process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
    private static void writeLogLine(BufferedWriter writer, long[] bytes, String line, Object logLock) throws IOException {
        synchronized (logLock) {
            if (bytes[0] >= MAX_SESSION_LOG_BYTES) {
                return;
            }
            byte[] encoded = (line + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes[0] + encoded.length > MAX_SESSION_LOG_BYTES) {
                return;
            }
            writer.write(line);
            writer.newLine();
            writer.flush();
            bytes[0] += encoded.length;
        }
    }
    static boolean isConnectedMarker(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("logon info v2")
            || lower.contains("logon info")
            || lower.contains("channelconnected")
            || lower.contains("tsg_state_connected")
            || lower.contains("displaycontrolcapspdu")
            || lower.contains("sdl_event_window_shown")
            || lower.contains("successfully connected")
            || lower.contains("postconnect")
            || lower.contains("rdp_client_connect_demand_active")
            || lower.contains("logoninfov1")
            || lower.contains("logoninfov2")
            || lower.contains("demand_active")
            || lower.contains("connection_state_active")
            || lower.contains("handleshow")
            || lower.contains("activated");
    }

    static String redactLogLine(String line) {
        String redacted = line.replaceAll("(?i)(Authorization\\s*(?:[:=]\\s*|\\s+)Bearer\\s+)[^\\s,]+", "$1" + REDACTED);
        redacted = redacted.replaceAll("(?i)(password|passwd|token|secret|authorization|bearer)([=:]\\s*|\\s+)(?!Bearer\\s+\\[REDACTED\\])[^\\s]+", "$1$2" + REDACTED);
        return redacted.replaceAll("(?i)([?&](?:code|token|access_token|refresh_token|id_token|secret|password)=)[^&\\s]+", "$1" + REDACTED);
    }

    /**
     * Retrieves an active session by resource ID.
     */
    public Optional<ActiveSession> getSession(String resourceId) {
        if (resourceId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(activeSessions.get(resourceId));
    }

    /**
     * Returns an unmodifiable view of all active sessions.
     */
    public Map<String, ActiveSession> getActiveSessions() {
        return Collections.unmodifiableMap(activeSessions);
    }

    /**
     * Stops an active session by resource ID.
     */
    public void stopSession(String resourceId) {
        if (resourceId == null) return;
        Object lock = sessionLocks.computeIfAbsent(resourceId, ignored -> new Object());
        synchronized (lock) {
            ActiveSession session = activeSessions.remove(resourceId);
            if (session != null) {
                session.stop();
                updateStatus(session, null, SessionStatus.DISCONNECTED, "Session disconnected");
            }
        }
    }

    /** Stops all active sessions. */
    public void stopAllSessions() {
        for (String id : List.copyOf(activeSessions.keySet())) stopSession(id);
    }

    private void updateStatus(ActiveSession session, SessionListener listener, SessionStatus newStatus, String message) {
        SessionStatus old = session.status();
        if (old == newStatus) {
            return;
        }
        session.setStatus(newStatus);
        emitEvent(listener, new SessionEvent.StatusChanged(session.sessionId(), old, newStatus, message));
    }

    private void emitEvent(SessionListener listener, SessionEvent event) {
        if (listener != null) {
            try {
                listener.onSessionEvent(event);
            } catch (Exception e) {
                System.err.println("Error in session listener: " + e.getMessage());
            }
        }
        for (SessionListener global : globalListeners) {
            try {
                global.onSessionEvent(event);
            } catch (Exception e) {
                System.err.println("Error in global session listener: " + e.getMessage());
            }
        }
    }


    private static Optional<String> detectMonitorSelection(FreeRdpInfo freeRdp) {
        String executable = freeRdp.binaryPath() != null
            ? freeRdp.binaryPath().toString()
            : freeRdp.flavor().getExecutableName();
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "/list:monitor")
                .redirectErrorStream(true)
                .start();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
                return Optional.empty();
            }
            String output;
            try (var input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            Pattern idPattern = Pattern.compile("\\[(\\d+)]");
            List<String> ids = new ArrayList<>();
            String primary = null;
            for (String line : output.lines().toList()) {
                Matcher matcher = idPattern.matcher(line);
                if (matcher.find()) {
                    String id = matcher.group(1);
                    ids.add(id);
                    if (line.contains("*") && primary == null) {
                        primary = id;
                    }
                }
            }
            if (primary != null) {
                ids.remove(primary);
                ids.addFirst(primary);
            }
            return ids.isEmpty() ? Optional.empty() : Optional.of(String.join(",", ids));
        } catch (InterruptedException e) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            if (process != null && process.isAlive()) process.destroyForcibly();
            return Optional.empty();
        }
    }
}
