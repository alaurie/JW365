package org.alaurie.jw365.rdp;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Handle to an active running RDP session process.
 */
public final class ActiveSession {

    private final String sessionId;
    private final String resourceTitle;
    private final Process process;
    private final Path logFile;
    private final Instant startTime;
    private final AtomicReference<SessionStatus> status;
    private final java.util.concurrent.atomic.AtomicBoolean userInitiatedStop = new java.util.concurrent.atomic.AtomicBoolean(false);

    public ActiveSession(
        String sessionId,
        String resourceTitle,
        Process process,
        Path logFile,
        SessionStatus initialStatus
    ) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.resourceTitle = Objects.requireNonNull(resourceTitle, "resourceTitle must not be null");
        this.process = Objects.requireNonNull(process, "process must not be null");
        this.logFile = logFile;
        this.startTime = Instant.now();
        this.status = new AtomicReference<>(initialStatus != null ? initialStatus : SessionStatus.STARTING);
    }

    public String sessionId() {
        return sessionId;
    }

    public String resourceTitle() {
        return resourceTitle;
    }

    public Process process() {
        return process;
    }

    public Path logFile() {
        return logFile;
    }

    public Instant startTime() {
        return startTime;
    }

    public SessionStatus status() {
        return status.get();
    }

    public void setStatus(SessionStatus newStatus) {
        this.status.set(newStatus);
    }

    public boolean isAlive() {
        return process.isAlive();
    }
    /**
     * Writes input to the active session process's standard input stream.
     */
    public void writeInput(String text) {
        if (!process.isAlive() || text == null) {
            return;
        }
        try {
            java.io.OutputStream os = process.getOutputStream();
            os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();
        } catch (java.io.IOException e) {
            System.err.println("Warning: Failed to write to process stdin: " + e.getMessage());
        }
    }


    public boolean isUserInitiatedStop() {
        return userInitiatedStop.get();
    }

    /**
     * Terminates the session gracefully, falling back to forceful kill after 3 seconds.
     */
    public void stop() {
        userInitiatedStop.set(true);
        setStatus(SessionStatus.DISCONNECTED);
        if (!process.isAlive()) {
            return;
        }

        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
