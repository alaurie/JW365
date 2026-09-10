package org.alaurie.jw365.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthCallbackTest {

    @Test
    @DisplayName("Authorization callback decodes code and validates state")
    void parsesSuccessfulCallback() {
        OAuthCallback callback = OAuthCallback.parse(
            "https://login.microsoftonline.com/common/oauth2/nativeclient"
                + "?code=abc%2B123&state=expected%20state"
        ).orElseThrow();

        assertThat(callback.isSuccess()).isTrue();
        assertThat(callback.code()).isEqualTo("abc+123");
        assertThat(callback.matchesState("expected state")).isTrue();
        assertThat(callback.matchesState("wrong state")).isFalse();
    }

    @Test
    @DisplayName("Authorization errors are surfaced and never treated as successful codes")
    void parsesErrorCallback() {
        OAuthCallback callback = OAuthCallback.parse(
            "https://login.microsoftonline.com/common/oauth2/nativeclient"
                + "?error=access_denied&error_description=User%20cancelled&state=state"
        ).orElseThrow();

        assertThat(callback.hasError()).isTrue();
        assertThat(callback.isSuccess()).isFalse();
        assertThat(callback.errorMessage()).isEqualTo("User cancelled");
    }

    @Test
    @DisplayName("Malformed and ambiguous callbacks are rejected")
    void rejectsMalformedOrDuplicateParameters() {
        assertThat(OAuthCallback.parse("not a uri")).isEmpty();
        assertThat(OAuthCallback.parse(
            "https://login.microsoftonline.com/common/oauth2/nativeclient?code=a&code=b&state=s"
        )).isEmpty();
    }
}
