package org.alaurie.jw365.gui.view;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionAuthDialogTest {

    @Test
    @DisplayName("validateInitialAuthUrl accepts FreeRDP AAD authorization URLs without state parameter")
    void testFreeRdpAuthUrlWithoutStateAccepted() {
        String freerdpUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
            + "?client_id=ce5860e3-2895-46ff-a5ff-98eb79743c7b&response_type=code"
            + "&scope=https%3A%2F%2Fwww.wvd.microsoft.com%2F.default+offline_access+openid+profile"
            + "&redirect_uri=https%3A%2F%2Flogin.microsoftonline.com%2Fcommon%2Foauth2%2Fnativeclient";

        String validated = SessionAuthDialog.validateInitialAuthUrl(freerdpUrl);
        assertThat(validated).isEqualTo(freerdpUrl);
    }

    @Test
    @DisplayName("validateInitialAuthUrl accepts authorization URLs with state parameter")
    void testAuthUrlWithStateAccepted() {
        String oauthUrl = "https://login.microsoftonline.com/organizations/oauth2/v2.0/authorize"
            + "?client_id=12345&response_type=code&state=secure_random_state&redirect_uri=https%3A%2F%2Flogin.microsoftonline.com%2Fcommon%2Foauth2%2Fnativeclient";

        String validated = SessionAuthDialog.validateInitialAuthUrl(oauthUrl);
        assertThat(validated).isEqualTo(oauthUrl);
    }

    @Test
    @DisplayName("validateInitialAuthUrl rejects non-Microsoft host and insecure schemes")
    void testHostileUrlsRejected() {
        assertThatThrownBy(() -> SessionAuthDialog.validateInitialAuthUrl("http://login.microsoftonline.com/auth?client_id=123"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Refusing non-Microsoft");

        assertThatThrownBy(() -> SessionAuthDialog.validateInitialAuthUrl("https://evil.attacker.com/auth?client_id=123"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Refusing non-Microsoft");

        assertThatThrownBy(() -> SessionAuthDialog.validateInitialAuthUrl("https://login.microsoftonline.com"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing query parameters");
    }

    @Test
    @DisplayName("extractRedirectUri correctly extracts and decodes redirect_uri parameter")
    void testExtractRedirectUri() {
        String authUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
            + "?client_id=xyz&redirect_uri=https%3A%2F%2Flogin.microsoftonline.com%2Fcommon%2Foauth2%2Fnativeclient&foo=bar";

        Optional<URI> redirectUri = SessionAuthDialog.extractRedirectUri(authUrl);
        assertThat(redirectUri).isPresent();
        assertThat(redirectUri.get()).isEqualTo(URI.create("https://login.microsoftonline.com/common/oauth2/nativeclient"));

        String noRedirectUrl = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=xyz";
        assertThat(SessionAuthDialog.extractRedirectUri(noRedirectUrl)).isEmpty();
    }

    @Test
    @DisplayName("isCallbackAcceptable accepts callback without state when expectedState is null")
    void testCallbackWithoutStateAcceptedWhenExpectedStateNull() {
        URI redirectUri = URI.create("https://login.microsoftonline.com/common/oauth2/nativeclient");
        String callbackUrl = "https://login.microsoftonline.com/common/oauth2/nativeclient?code=AQABAAAA_CODE_123";

        boolean acceptable = SessionAuthDialog.isCallbackAcceptable(callbackUrl, null, redirectUri);
        assertThat(acceptable).isTrue();
    }

    @Test
    @DisplayName("isCallbackAcceptable validates state when expectedState is provided")
    void testCallbackStateValidationWhenExpectedStatePresent() {
        URI redirectUri = URI.create("https://login.microsoftonline.com/common/oauth2/nativeclient");
        String validCallback = "https://login.microsoftonline.com/common/oauth2/nativeclient?code=123&state=expected_secret_state";
        String invalidCallback = "https://login.microsoftonline.com/common/oauth2/nativeclient?code=123&state=wrong_state";
        String missingStateCallback = "https://login.microsoftonline.com/common/oauth2/nativeclient?code=123";

        assertThat(SessionAuthDialog.isCallbackAcceptable(validCallback, "expected_secret_state", redirectUri)).isTrue();
        assertThat(SessionAuthDialog.isCallbackAcceptable(invalidCallback, "expected_secret_state", redirectUri)).isFalse();
        assertThat(SessionAuthDialog.isCallbackAcceptable(missingStateCallback, "expected_secret_state", redirectUri)).isFalse();
    }

    @Test
    @DisplayName("isCallbackAcceptable accepts OAuth error response")
    void testCallbackWithErrorAccepted() {
        URI redirectUri = URI.create("https://login.microsoftonline.com/common/oauth2/nativeclient");
        String errorCallback = "https://login.microsoftonline.com/common/oauth2/nativeclient?error=access_denied&error_description=User+cancelled";

        assertThat(SessionAuthDialog.isCallbackAcceptable(errorCallback, null, redirectUri)).isTrue();
    }

    @Test
    @DisplayName("isCallbackAcceptable rejects non-redirect or malformed URLs")
    void testCallbackRejectsInvalidUrls() {
        URI redirectUri = URI.create("https://login.microsoftonline.com/common/oauth2/nativeclient");

        assertThat(SessionAuthDialog.isCallbackAcceptable("https://evil.com/callback?code=123", null, redirectUri)).isFalse();
        assertThat(SessionAuthDialog.isCallbackAcceptable("https://login.microsoftonline.com/other/path?code=123", null, redirectUri)).isFalse();
        assertThat(SessionAuthDialog.isCallbackAcceptable(null, null, redirectUri)).isFalse();
        assertThat(SessionAuthDialog.isCallbackAcceptable("", null, redirectUri)).isFalse();
    }

    @Test
    @DisplayName("appendLoginHintIfMissing appends URL-encoded login_hint when absent")
    void testAppendLoginHintWhenAbsent() {
        String url = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=xyz&response_type=code";
        String updated = SessionAuthDialog.appendLoginHintIfMissing(url, "alex.laurie@arinco.com.au");
        assertThat(updated).isEqualTo(url + "&login_hint=alex.laurie%40arinco.com.au");
    }

    @Test
    @DisplayName("appendLoginHintIfMissing preserves existing login_hint")
    void testAppendLoginHintPreservesExisting() {
        String url = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=xyz&login_hint=user%40test.com";
        String updated = SessionAuthDialog.appendLoginHintIfMissing(url, "alex.laurie@arinco.com.au");
        assertThat(updated).isEqualTo(url);
    }

    @Test
    @DisplayName("appendLoginHintIfMissing strips prompt=select_account when loginHint is supplied")
    void testAppendLoginHintStripsSelectAccountPrompt() {
        String url = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=xyz&prompt=select_account&response_type=code";
        String updated = SessionAuthDialog.appendLoginHintIfMissing(url, "alex.laurie@arinco.com.au");
        assertThat(updated).doesNotContain("prompt=select_account");
        assertThat(updated).contains("login_hint=alex.laurie%40arinco.com.au");
        assertThat(updated).contains("client_id=xyz");
        assertThat(updated).contains("response_type=code");
    }

    @Test
    @DisplayName("appendLoginHintIfMissing handles null and blank loginHint gracefully")
    void testAppendLoginHintNullOrBlank() {
        String url = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=xyz";
        assertThat(SessionAuthDialog.appendLoginHintIfMissing(url, null)).isEqualTo(url);
        assertThat(SessionAuthDialog.appendLoginHintIfMissing(url, "")).isEqualTo(url);
        assertThat(SessionAuthDialog.appendLoginHintIfMissing(url, "   ")).isEqualTo(url);
        assertThat(SessionAuthDialog.appendLoginHintIfMissing(null, "user@test.com")).isNull();
    }

    @Test
    @DisplayName("appendLoginHintIfMissing preserves URL fragments")
    void testAppendLoginHintPreservesFragment() {
        String url = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=xyz#section";
        String updated = SessionAuthDialog.appendLoginHintIfMissing(url, "user@test.com");
        assertThat(updated).isEqualTo("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=xyz&login_hint=user%40test.com#section");
    }
}
