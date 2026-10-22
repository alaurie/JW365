package org.alaurie.jw365.gui.view;

import org.alaurie.jw365.auth.Fido2Cli.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebAuthnBridgeTest {

    @Test
    @DisplayName("Polyfill requests parse into an assertion request")
    void parsesRequest() {
        Request request = WebAuthnBridge.parseRequest("{\"origin\":\"https://login.microsoft.com\",\"rpId\":\"login.microsoft.com\",\"challenge\":\"YWJj\",\"allowCredentials\":[\"aWQx\",\"aWQy\"],\"userVerification\":\"required\",\"timeout\":600000}");

        assertThat(request.origin()).isEqualTo("https://login.microsoft.com");
        assertThat(request.rpId()).isEqualTo("login.microsoft.com");
        assertThat(request.challenge()).isEqualTo("YWJj");
        assertThat(request.allowCredentials()).containsExactly("aWQx", "aWQy");
        assertThat(request.requiresUserVerification()).isTrue();
        assertThat(request.timeoutMs()).isEqualTo(600000);
    }

    @Test
    @DisplayName("Missing optional fields fall back to spec defaults")
    void defaultsMissingFields() {
        Request request = WebAuthnBridge.parseRequest("{\"origin\":\"https://login.microsoft.com\",\"rpId\":\"login.microsoft.com\",\"challenge\":\"YWJj\"}");

        assertThat(request.allowCredentials()).isEmpty();
        assertThat(request.userVerification()).isEqualTo("preferred");
        assertThat(request.timeoutMs()).isZero();
    }

    @Test
    @DisplayName("Only the Microsoft sign-in origins are allowed")
    void allowedOrigins() {
        assertThat(WebAuthnBridge.ALLOWED_ORIGINS).contains("https://login.microsoft.com", "https://login.microsoftonline.com", "https://login.live.com");
        assertThat(WebAuthnBridge.ALLOWED_ORIGINS).doesNotContain("https://outlook.office.com");
    }
}
