package org.alaurie.jw365.rdp;

/**
 * Lifecycle status of an RDP connection.
 */
public enum SessionStatus {
    IDLE("Idle"),
    STARTING("Starting..."),
    CONNECTING("Connecting..."),
    CONNECTED("Connected"),
    DISCONNECTED("Disconnected"),
    FAILED("Failed");

    private final String label;

    SessionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isActive() {
        return this == STARTING || this == CONNECTING || this == CONNECTED;
    }
}
