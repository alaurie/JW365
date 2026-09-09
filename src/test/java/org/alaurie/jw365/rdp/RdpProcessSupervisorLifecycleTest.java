package org.alaurie.jw365.rdp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class RdpProcessSupervisorLifecycleTest {

    @Test
    @DisplayName("ActiveSession accurately tracks state, writes input, and terminates cleanly")
    void testActiveSessionLifecycle(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("test_session.log");

        // Spawn a lightweight long-running echo process
        Process process = new ProcessBuilder("cat").start();

        ActiveSession session = new ActiveSession(
            "test-res-1",
            "Test Cloud PC",
            process,
            logFile,
            SessionStatus.STARTING
        );

        assertThat(session.sessionId()).isEqualTo("test-res-1");
        assertThat(session.resourceTitle()).isEqualTo("Test Cloud PC");
        assertThat(session.status()).isEqualTo(SessionStatus.STARTING);
        assertThat(session.isAlive()).isTrue();

        session.setStatus(SessionStatus.CONNECTING);
        assertThat(session.status()).isEqualTo(SessionStatus.CONNECTING);
        assertThat(session.status().isActive()).isTrue();

        session.setStatus(SessionStatus.CONNECTED);
        assertThat(session.status()).isEqualTo(SessionStatus.CONNECTED);
        assertThat(session.status().isActive()).isTrue();

        // Test writing input to process stdin
        session.writeInput("test input line\n");

        session.stop();
        assertThat(session.isAlive()).isFalse();
    }

    @Test
    @DisplayName("SessionEvent sealed hierarchy pattern matches all event cases cleanly")
    void testSessionEventPatternMatching() {
        AtomicBoolean authHandled = new AtomicBoolean(false);
        AtomicReference<String> passedUrl = new AtomicReference<>();

        SessionEvent event = new SessionEvent.AuthRequired(
            "session-abc",
            "https://login.microsoftonline.com/authorize?foo=bar",
            url -> {
                passedUrl.set(url);
                authHandled.set(true);
            }
        );

        assertThat(event.sessionId()).isEqualTo("session-abc");

        // Pattern match
        switch (event) {
            case SessionEvent.AuthRequired(var id, var url, var submitter) -> {
                assertThat(id).isEqualTo("session-abc");
                assertThat(url).contains("https://login.microsoftonline.com");
                submitter.accept("https://login.microsoftonline.com/nativeclient?code=12345");
            }
            case SessionEvent.Started s -> throw new AssertionError("Unexpected event: " + s);
            case SessionEvent.StatusChanged sc -> throw new AssertionError("Unexpected event: " + sc);
            case SessionEvent.OutputLine ol -> throw new AssertionError("Unexpected event: " + ol);
            case SessionEvent.Exited ex -> throw new AssertionError("Unexpected event: " + ex);
        }

        assertThat(authHandled.get()).isTrue();
        assertThat(passedUrl.get()).isEqualTo("https://login.microsoftonline.com/nativeclient?code=12345");
    }

    @Test
    @DisplayName("RdpProcessSupervisor registers listeners and manages session handles")
    void testSupervisorListeners() {
        RdpProcessSupervisor supervisor = new RdpProcessSupervisor();
        SessionListener listener = event -> {};
        supervisor.addGlobalListener(listener);

        assertThat(supervisor.getActiveSessions()).isEmpty();
        assertThat(supervisor.getSession("non-existent")).isEmpty();

        supervisor.removeGlobalListener(listener);
    }
}
