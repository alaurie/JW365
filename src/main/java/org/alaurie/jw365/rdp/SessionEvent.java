package org.alaurie.jw365.rdp;

import java.util.function.Consumer;

/**
 * Sealed hierarchy of lifecycle events emitted by active RDP sessions.
 */
public sealed interface SessionEvent {

    String sessionId();

    record Started(String sessionId, ProcessHandle processHandle) implements SessionEvent {
    }

    record StatusChanged(
        String sessionId,
        SessionStatus oldStatus,
        SessionStatus newStatus,
        String message
    ) implements SessionEvent {
    }

    record OutputLine(String sessionId, String line, boolean isError) implements SessionEvent {
    }

    record Exited(String sessionId, int exitCode, String message) implements SessionEvent {
    }

    record AuthRequired(
        String sessionId,
        String authUrl,
        Consumer<String> submitRedirectUrl
    ) implements SessionEvent {
    }
}
