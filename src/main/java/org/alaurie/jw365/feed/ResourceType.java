package org.alaurie.jw365.feed;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Type of resource published in the AVD / Windows 365 workspace feed.
 */
public enum ResourceType {
    DESKTOP("Desktop"),
    REMOTE_APP("RemoteApp"),
    UNKNOWN("Unknown");

    private final String value;

    ResourceType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ResourceType fromString(String val) {
        if (val == null) {
            return UNKNOWN;
        }
        for (ResourceType type : values()) {
            if (type.value.equalsIgnoreCase(val.trim())) {
                return type;
            }
        }
        return UNKNOWN;
    }

    public boolean isDesktop() {
        return this == DESKTOP;
    }

    public boolean isRemoteApp() {
        return this == REMOTE_APP;
    }
}
