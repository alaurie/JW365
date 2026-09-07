package org.alaurie.jw365.rdp;

/**
 * Listener interface for receiving lifecycle events from active RDP sessions.
 */
@FunctionalInterface
public interface SessionListener {
    void onSessionEvent(SessionEvent event);
}
