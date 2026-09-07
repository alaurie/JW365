package org.alaurie.jw365.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;

/**
 * Entra ID Device Code authorization response.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceCodeResponse(
    @JsonProperty("device_code") String deviceCode,
    @JsonProperty("user_code") String userCode,
    @JsonProperty("verification_uri") URI verificationUri,
    @JsonProperty("expires_in") long expiresIn,
    @JsonProperty("interval") int interval,
    @JsonProperty("message") String message
) {

    public int pollIntervalSeconds() {
        return interval > 0 ? interval : 5;
    }
}
