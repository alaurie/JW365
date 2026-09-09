package org.alaurie.jw365.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthClientTest {

    @Test
    @DisplayName("PKCE challenge generates high entropy verifier and valid S256 challenge")
    void testPkceChallengeGeneration() {
        PkceChallenge challenge = PkceChallenge.create();

        assertThat(challenge.codeVerifier()).isNotBlank().hasSizeGreaterThanOrEqualTo(43);
        assertThat(challenge.codeChallenge()).isNotBlank();
        assertThat(challenge.state()).isNotBlank();

        // Verify S256 computation matches
        String computed = PkceChallenge.computeS256(challenge.codeVerifier());
        assertThat(challenge.codeChallenge()).isEqualTo(computed);

        // Verify URL-safe characters only
        assertThat(challenge.codeVerifier()).matches("^[a-zA-Z0-9_-]+$");
        assertThat(challenge.codeChallenge()).matches("^[a-zA-Z0-9_-]+$");
        assertThat(challenge.state()).matches("^[a-zA-Z0-9_-]+$");
    }

    @Test
    @DisplayName("TokenResponse expiration logic operates accurately")
    void testTokenResponseExpiration() {
        long now = Instant.now().getEpochSecond();
        TokenResponse validTokens = new TokenResponse(
            "access_token_123",
            "refresh_token_456",
            "id_token_789",
            "Bearer",
            3600,
            "https://www.wvd.microsoft.com/.default",
            now
        );

        assertThat(validTokens.isExpired()).isFalse();
        assertThat(validTokens.isExpiringSoon()).isFalse();
        assertThat(validTokens.hasRefreshToken()).isTrue();
        assertThat(validTokens.expiresAt()).isEqualTo(Instant.ofEpochSecond(now + 3600));

        TokenResponse expiringTokens = new TokenResponse(
            "access_token_123",
            "refresh_token_456",
            "id_token_789",
            "Bearer",
            120, // 2 minutes left
            "https://www.wvd.microsoft.com/.default",
            now
        );

        assertThat(expiringTokens.isExpired()).isFalse();
        assertThat(expiringTokens.isExpiringSoon()).isTrue();
        assertThat(expiringTokens.isExpiringWithin(Duration.ofMinutes(5))).isTrue();

        TokenResponse expiredTokens = new TokenResponse(
            "access_token_123",
            "refresh_token_456",
            "id_token_789",
            "Bearer",
            -10, // already expired
            "https://www.wvd.microsoft.com/.default",
            now
        );

        assertThat(expiredTokens.isExpired()).isTrue();
    }

    @Test
    @DisplayName("JwtClaimsParser accurately extracts UPN, names, emails, and tenant ID from JWT payload")
    void testJwtClaimsParser() {
        String jsonPayload = """
            {
              "aud": "a85cf173-4192-42f8-81fa-777a763e6e2c",
              "iss": "https://login.microsoftonline.com/contoso-tenant-id/v2.0",
              "upn": "alex@contoso.onmicrosoft.com",
              "preferred_username": "alex@contoso.com",
              "name": "Alex Contoso",
              "email": "alex.personal@outlook.com",
              "tid": "00000000-0000-0000-0000-000000000001",
              "oid": "11111111-1111-1111-1111-111111111111",
              "roles": ["CloudPCUser", "AVDUser"]
            }
            """;

        String base64Payload = Base64.getUrlEncoder().withoutPadding().encodeToString(jsonPayload.getBytes(StandardCharsets.UTF_8));
        String syntheticJwt = "eyJhbGciOiJSUzI1NiJ9." + base64Payload + ".signature123";

        UserClaims claims = JwtClaimsParser.parseIdToken(syntheticJwt);

        assertThat(claims.upn()).isEqualTo("alex@contoso.onmicrosoft.com");
        assertThat(claims.preferredUsername()).isEqualTo("alex@contoso.com");
        assertThat(claims.name()).isEqualTo("Alex Contoso");
        assertThat(claims.email()).isEqualTo("alex.personal@outlook.com");
        assertThat(claims.tenantId()).isEqualTo("00000000-0000-0000-0000-000000000001");
        assertThat(claims.objectId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(claims.roles()).containsExactly("CloudPCUser", "AVDUser");

        assertThat(claims.displayIdentity()).isEqualTo("alex@contoso.onmicrosoft.com");
        assertThat(claims.rdpUsername()).isEqualTo("alex@contoso.onmicrosoft.com");
    }

    @Test
    @DisplayName("OAuthClient builds valid authorize URL with PKCE parameters")
    void testAuthorizeUrlBuilder() {
        OAuthClient client = new OAuthClient();
        PkceChallenge challenge = PkceChallenge.create();

        URI authUri = client.buildAuthorizeUrl("contoso-tenant", challenge, "alex@contoso.com");

        assertThat(authUri.getHost()).isEqualTo("login.microsoftonline.com");
        assertThat(authUri.getPath()).isEqualTo("/contoso-tenant/oauth2/v2.0/authorize");

        String rawQuery = authUri.getRawQuery();
        assertThat(rawQuery).contains("client_id=" + OAuthClient.DEFAULT_CLIENT_ID);
        assertThat(rawQuery).contains("response_type=code");
        assertThat(rawQuery).contains("code_challenge_method=S256");
        assertThat(rawQuery).contains("code_challenge=" + challenge.codeChallenge());
        assertThat(rawQuery).contains("state=" + challenge.state());
        assertThat(rawQuery).contains("login_hint=alex%40contoso.com");
        assertThat(rawQuery).contains("prompt=select_account");
    }
}
