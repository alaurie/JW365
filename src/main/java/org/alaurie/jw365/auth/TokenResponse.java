package org.alaurie.jw365.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;

/**
 * Token response returned by Entra ID OAuth 2.0 token endpoint.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("id_token") String idToken,
    @JsonProperty("token_type") String tokenType,
    @JsonProperty("expires_in") long expiresIn,
    @JsonProperty("scope") String scope,
    @JsonProperty("obtained_epoch_sec") long obtainedEpochSec
) {

    public TokenResponse {
        if (obtainedEpochSec <= 0) {
            obtainedEpochSec = Instant.now().getEpochSecond();
        }
    }


    @JsonIgnore
    public Instant expiresAt() {
        return Instant.ofEpochSecond(obtainedEpochSec + expiresIn);
    }

    @JsonIgnore
    public boolean isExpired() {
        return isExpiringWithin(Duration.ZERO);
    }

    @JsonIgnore
    public boolean isExpiringSoon() {
        return isExpiringWithin(Duration.ofMinutes(5));
    }

    @JsonIgnore
    public boolean isExpiringWithin(Duration threshold) {
        Instant expiryWithThreshold = expiresAt().minus(threshold);
        return Instant.now().isAfter(expiryWithThreshold);
    }

    @JsonIgnore
    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.isBlank();
    }
}
