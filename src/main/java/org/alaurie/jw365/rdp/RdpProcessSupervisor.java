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

    private final Map<String, ActiveSession> activeSessions = new ConcurrentHashMap<>();
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

        // Network auto-optimization
        cmd.add("/network:auto");

        // Async updates and channel processing
        if (config.asyncUpdate()) {
            cmd.add("+async-update");
            cmd.add("+async-channels");
        }

        // Automatic reconnection on network blips
        if (config.autoReconnect()) {
            cmd.add("+auto-reconnect");
            cmd.add("/auto-reconnect-max-retries:10");
        }

        // H.264 / RDP8 progressive rendering pipeline
        if (config.gfxProgressive()) {
            cmd.add("/gfx:progressive");
        }

        // Display color depth and GDI renderer.
        cmd.add("/gdi:hw");
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

        String sessionId = resource.id();
        // Stop any existing session for this resource ID first
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

        Path logFile = XdgPaths.logsDir().resolve("session_" + resource.sanitizedFileName() + "_" + System.currentTimeMillis() + ".log");

        ProcessBuilder pb = new ProcessBuilder(processCommand);
        pb.redirectErrorStream(true);

        // Pass through Wayland and X11 display environment
        Map<String, String> env = pb.environment();
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        String display = System.getenv("DISPLAY");
        if (display != null && !display.isBlank()) {
            env.put("DISPLAY", display);
        }
        if (waylandDisplay != null && !waylandDisplay.isBlank()) {
            env.put("WAYLAND_DISPLAY", waylandDisplay);
            boolean x11Fullscreen = freeRdp.isFlatpak() && config.fullscreen();
            if ((config.multiMonitor() || x11Fullscreen) && display != null && !display.isBlank()) {
                // Flatpak SDL fullscreen is more stable through XWayland on mixed Wayland/X11 desktops.
                env.put("SDL_VIDEODRIVER", "x11");
            } else {
                env.put("SDL_VIDEODRIVER", "wayland,x11");
            }
        }
        String xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
        if (xdgRuntime != null && !xdgRuntime.isBlank()) {
            env.put("XDG_RUNTIME_DIR", xdgRuntime);
        }

        // PipeWire / PulseAudio direct native socket path
        String pulseServer = System.getenv("PULSE_SERVER");
        if (pulseServer != null && !pulseServer.isBlank()) {
            env.put("PULSE_SERVER", pulseServer);
        } else if (xdgRuntime != null && !xdgRuntime.isBlank() && Files.exists(Path.of(xdgRuntime, "pulse", "native"))) {
            env.put("PULSE_SERVER", "unix:" + xdgRuntime + "/pulse/native");
        }
        Process process = pb.start();

        ActiveSession session = new ActiveSession(
            sessionId,
            resource.title(),
            process,
            logFile,
            SessionStatus.STARTING
        );

        activeSessions.put(sessionId, session);

        // Notify started
        emitEvent(listener, new SessionEvent.Started(sessionId, process.toHandle()));
        updateStatus(session, listener, SessionStatus.CONNECTING, "Connecting to " + resource.title() + "...");

        // Start Virtual Thread to monitor output and lifecycle
        Thread.ofVirtual().name("rdp-watcher-" + resource.sanitizedFileName()).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter logWriter = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {

                logWriter.write("=== JW365 Session Log for " + resource.title() + " ===\n");
                logWriter.write("Command: " + String.join(" ", rawCommand) + "\n");
                logWriter.write("Started: " + Instant.now() + "\n\n");
                logWriter.flush();

                String line;
                while ((line = reader.readLine()) != null) {
                    logWriter.write(line);
                    logWriter.newLine();
                    logWriter.flush();

                    emitEvent(listener, new SessionEvent.OutputLine(sessionId, line, false));

                    // Intercept FreeRDP OAuth authorization prompt
                    if (line.contains("Browse to: ")) {
                        String authUrl = line.substring(line.indexOf("Browse to: ") + "Browse to: ".length()).trim();
                        updateStatus(session, listener, SessionStatus.CONNECTING, "Authenticating Cloud PC session...");
                        emitEvent(listener, new SessionEvent.AuthRequired(sessionId, authUrl, redirectUrl -> {
                            session.writeInput(redirectUrl + "\n");
                            // Verify session remains alive after auth submission and transition to Connected
                            Thread.ofVirtual().name("auth-confirm-" + sessionId).start(() -> {
                                try {
                                    Thread.sleep(3000);
                                    if (session.isAlive() && session.status() != SessionStatus.CONNECTED) {
                                        updateStatus(session, listener, SessionStatus.CONNECTED, "Connected");
                                    }
                                } catch (InterruptedException ignored) {
                                }
                            });
                        }));
                    }

                    // Inspect line for connection established state
                    String lower = line.toLowerCase();
                    if (lower.contains("reconnect") || lower.contains("reconnecting")) {
                        updateStatus(session, listener, SessionStatus.RECONNECTING, "Reconnecting...");
                    }
                    if (lower.contains("activated") ||
                        lower.contains("channelconnected") ||
                        lower.contains("logon info") ||
                        lower.contains("connection established") ||
                        lower.contains("connected to") ||
                        lower.contains("successfully connected") ||
                        lower.contains("displaycontrolcapspdu") ||
                        lower.contains("tsg_state_connected") ||
                        lower.contains("display driver in the remote session started up successfully") ||
                        lower.contains("sdl_event_window_shown")) {
                        if (session.status() != SessionStatus.CONNECTED) {
                            updateStatus(session, listener, SessionStatus.CONNECTED, "Connected");
                        }
                    }
                }
                int exitCode = process.waitFor();
                logWriter.write("\n=== Process exited with code " + exitCode + " at " + Instant.now() + " ===\n");
                logWriter.flush();

                if (session.isUserInitiatedStop() || exitCode == 0 || exitCode == 143 || exitCode == 130 || exitCode == 129) {
                    updateStatus(session, listener, SessionStatus.DISCONNECTED, "Session disconnected");
                } else {
                    updateStatus(session, listener, SessionStatus.FAILED, "Session exited with error code " + exitCode);
                }

                emitEvent(listener, new SessionEvent.Exited(sessionId, exitCode, "Exit code " + exitCode));
            } catch (Exception e) {
                if (session.isUserInitiatedStop()) {
                    updateStatus(session, listener, SessionStatus.DISCONNECTED, "Session disconnected");
                } else {
                    updateStatus(session, listener, SessionStatus.FAILED, "Session monitoring error: " + e.getMessage());
                }
            } finally {
                activeSessions.remove(sessionId, session);
            }
        });

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
        ActiveSession session = activeSessions.remove(resourceId);
        if (session != null) {
            session.stop();
            updateStatus(session, null, SessionStatus.DISCONNECTED, "Session disconnected");
        }
    }

    /**
     * Stops all active sessions.
     */
    public void stopAllSessions() {
        for (ActiveSession session : activeSessions.values()) {
            session.stop();
        }
        activeSessions.clear();
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
        try {
            Process process = new ProcessBuilder(executable, "/list:monitor")
                .redirectErrorStream(true)
                .start();
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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
                ids.add(0, primary);
            }
            return ids.isEmpty() ? Optional.empty() : Optional.of(String.join(",", ids));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
    /**
     * Ensures FreeRDP SDL client configuration disables hazardous default hotkeys (such as Right Shift + D = Disconnect).
     */
    public static void ensureSdlConfig() {
        String safeConfig = """
            {
              "SDL_KeyModMask": ["KMOD_NONE"],
              "SDL_Disconnect": ["SDL_SCANCODE_F12"],
              "SDL_Minimize": ["SDL_SCANCODE_F11"],
              "SDL_Fullscreen": ["SDL_SCANCODE_F10"]
            }
            """;

        // 1. Native config path (~/.config/freerdp/sdl-freerdp.json)
        try {
            String configHome = System.getenv("XDG_CONFIG_HOME");
            Path base = (configHome != null && !configHome.isBlank())
                ? Path.of(configHome)
                : Path.of(System.getProperty("user.home"), ".config");
            Path configFile = base.resolve("freerdp").resolve("sdl-freerdp.json");
            String existing = Files.exists(configFile) ? Files.readString(configFile) : "";
            if (!Files.exists(configFile) || existing.contains("KMOD_RSHIFT") || existing.contains("KMOD_RCTRL") || existing.contains("SDL_SCANCODE_D")) {
                Files.createDirectories(configFile.getParent());
                Files.writeString(configFile, safeConfig, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not configure native sdl-freerdp.json: " + e.getMessage());
        }

        // 2. Flatpak sandbox config path (~/.var/app/com.freerdp.FreeRDP/config/freerdp/sdl-freerdp.json)
        try {
            Path flatpakDir = Path.of(System.getProperty("user.home"), ".var", "app", "com.freerdp.FreeRDP", "config", "freerdp");
            if (Files.exists(flatpakDir.getParent())) {
                Path flatpakConfig = flatpakDir.resolve("sdl-freerdp.json");
                String existing = Files.exists(flatpakConfig) ? Files.readString(flatpakConfig) : "";
                if (!Files.exists(flatpakConfig) || existing.contains("KMOD_RSHIFT") || existing.contains("KMOD_RCTRL") || existing.contains("SDL_SCANCODE_D")) {
                    Files.createDirectories(flatpakDir);
                    Files.writeString(flatpakConfig, safeConfig, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
