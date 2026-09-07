package org.alaurie.jw365.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * User claims extracted from Entra ID ID Token or UserInfo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserClaims(
    @JsonProperty("upn") String upn,
    @JsonProperty("name") String name,
    @JsonProperty("email") String email,
    @JsonProperty("preferred_username") String preferredUsername,
    @JsonProperty("tid") String tenantId,
    @JsonProperty("oid") String objectId,
    @JsonProperty("roles") List<String> roles
) {

    /**
     * Returns the best available display identity (UPN, preferred_username, or email).
     */
    public String displayIdentity() {
        if (upn != null && !upn.isBlank()) {
            return upn;
        }
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        if (email != null && !email.isBlank()) {
            return email;
        }
        return name != null ? name : "Unknown User";
    }

    /**
     * Returns the username to pass to FreeRDP (/u: parameter).
     */
    public String rdpUsername() {
        if (upn != null && !upn.isBlank()) {
            return upn;
        }
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        return email != null ? email : "";
    }
}
