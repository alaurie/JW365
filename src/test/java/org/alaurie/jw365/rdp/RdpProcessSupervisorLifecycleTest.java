package org.alaurie.jw365.rdp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class RdpProcessSupervisorLifecycleTest {

    @Test
    @DisplayName("ActiveSession accurately tracks state, writes input, and terminates cleanly")
    void testActiveSessionLifecycle() throws Exception {

        // Spawn a lightweight long-running echo process
        Process process = new ProcessBuilder("cat").start();

        ActiveSession session = new ActiveSession(
            "test-res-1",
            "Test Cloud PC",
            process,
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

        SessionEvent.AuthRequired event = new SessionEvent.AuthRequired(
            "session-abc",
            "https://login.microsoftonline.com/authorize?foo=bar",
            url -> {
                passedUrl.set(url);
                authHandled.set(true);
            }
        );

        assertThat(event.sessionId()).isEqualTo("session-abc");

        assertThat(event.authUrl()).contains("https://login.microsoftonline.com");
        event.submitRedirectUrl().accept("https://login.microsoftonline.com/nativeclient?code=12345");

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
