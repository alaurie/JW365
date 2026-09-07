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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
            cmd.add(freeRdp.flatpakAppId() != null ? freeRdp.flatpakAppId() : "com.freerdp.FreeRDP");
        } else if (freeRdp.binaryPath() != null) {
            cmd.add(freeRdp.binaryPath().toString());
        } else {
            cmd.add("sdl-freerdp");
        }

        // RDP file path
        cmd.add(config.rdpFile().toAbsolutePath().toString());

        // AVD Gateway and Entra ID (AAD) authentication flags
        cmd.add("/gateway:type:arm");
        cmd.add("/sec:aad");

        if (config.username() != null && !config.username().isBlank()) {
            cmd.add("/u:" + config.username());
        }

        // Audio and Microphone
        if (config.sound()) {
            cmd.add("/sound:sys:pulse");
        }
        if (config.microphone()) {
            cmd.add("/microphone");
        }

        // Display settings
        if (config.fullscreen()) {
            cmd.add("/f");
        }
        if (config.scalePercent() > 0) {
            cmd.add("/scale-desktop:" + config.scalePercent());
        }
        if (config.multiMonitor()) {
            cmd.add("/multimon");
        }
        if (config.ignoreCert()) {
            cmd.add("/cert:ignore");
        }

        // Clipboard synchronization
        if (config.clipboard()) {
            cmd.add("+clipboard");
        }

        // Dynamic desktop resolution updates
        if (config.dynamicResolution()) {
            cmd.add("+dynamic-resolution");
        }

        // Log level
        cmd.add("/log-level:info");

        // Custom extra arguments
        if (config.extraArgs() != null) {
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
     * @return active session handle
     */
    public ActiveSession launch(
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

        // Ensure FreeRDP SDL client hotkeys don't intercept normal typing (e.g. Shift+D disconnect)
        if (freeRdp.flavor() == FreeRdpFlavor.SDL_FREERDP) {
            ensureSdlConfig();
        }

        List<String> rawCommand = buildCommandLine(freeRdp, config);
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
        if (waylandDisplay != null && !waylandDisplay.isBlank()) {
            env.put("WAYLAND_DISPLAY", waylandDisplay);
            env.put("SDL_VIDEODRIVER", "wayland,x11");
        }
        String display = System.getenv("DISPLAY");
        if (display != null && !display.isBlank()) {
            env.put("DISPLAY", display);
        }
        String xdgRuntime = System.getenv("XDG_RUNTIME_DIR");
        if (xdgRuntime != null && !xdgRuntime.isBlank()) {
            env.put("XDG_RUNTIME_DIR", xdgRuntime);
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

                if (exitCode == 0) {
                    updateStatus(session, listener, SessionStatus.DISCONNECTED, "Session ended normally");
                } else {
                    updateStatus(session, listener, SessionStatus.FAILED, "Session exited with error code " + exitCode);
                }

                emitEvent(listener, new SessionEvent.Exited(sessionId, exitCode, "Exit code " + exitCode));
            } catch (Exception e) {
                updateStatus(session, listener, SessionStatus.FAILED, "Session monitoring error: " + e.getMessage());
            } finally {
                activeSessions.remove(sessionId);
            }
        });

        return session;
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

    /**
     * Ensures FreeRDP SDL client configuration disables hazardous default hotkeys (such as Right Shift + D = Disconnect).
     */
    public static void ensureSdlConfig() {
        try {
            String configHome = System.getenv("XDG_CONFIG_HOME");
            Path base = (configHome != null && !configHome.isBlank())
                ? Path.of(configHome)
                : Path.of(System.getProperty("user.home"), ".config");
            Path freerdpDir = base.resolve("freerdp");
            Path configFile = freerdpDir.resolve("sdl-freerdp.json");

            if (!Files.exists(configFile)) {
                Files.createDirectories(freerdpDir);
                String safeConfig = """
                    {
                      "SDL_KeyModMask": ["KMOD_RCTRL"],
                      "SDL_Disconnect": ["SDL_SCANCODE_F12"],
                      "SDL_Minimize": ["SDL_SCANCODE_F11"],
                      "SDL_Fullscreen": ["SDL_SCANCODE_F10"]
                    }
                    """;
                Files.writeString(configFile, safeConfig, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not configure sdl-freerdp.json: " + e.getMessage());
        }
    }
}
