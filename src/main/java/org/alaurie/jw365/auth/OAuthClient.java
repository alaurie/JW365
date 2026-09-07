package org.alaurie.jw365.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * High-performance modern HTTP client for Entra ID (Azure AD) OAuth 2.0 authentication.
 */
public final class OAuthClient {

    /**
     * Official Microsoft Remote Desktop / AVD public Client ID.
     */
    public static final String DEFAULT_CLIENT_ID = "a85cf173-4192-42f8-81fa-777a763e6e2c";
    public static final String DEFAULT_TENANT = "organizations";
    public static final String DEFAULT_SCOPE = "https://www.wvd.microsoft.com/.default offline_access openid profile";
    public static final String REDIRECT_URI = "https://login.microsoftonline.com/common/oauth2/nativeclient";
    private static final String LOGIN_BASE = "https://login.microsoftonline.com/%s/oauth2/v2.0";

    private final String clientId;
    private final String scope;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OAuthClient() {
        this(DEFAULT_CLIENT_ID, DEFAULT_SCOPE, HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    public OAuthClient(String clientId, String scope, HttpClient httpClient) {
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.mapper = new ObjectMapper();
    }

    public String getClientId() {
        return clientId;
    }

    public String getScope() {
        return scope;
    }

    /**
     * Builds the interactive PKCE authorization URL to present to the user or embedded WebView.
     */
    public URI buildAuthorizeUrl(String tenant, PkceChallenge challenge, String loginHint) {
        return buildAuthorizeUrl(tenant, challenge, REDIRECT_URI, loginHint);
    }

    /**
     * Builds the interactive PKCE authorization URL with a specific redirect URI.
     */
    public URI buildAuthorizeUrl(String tenant, PkceChallenge challenge, String redirectUri, String loginHint) {
        String effectiveRedirect = (redirectUri != null && !redirectUri.isBlank()) ? redirectUri : REDIRECT_URI;
        URI baseUri = buildEndpointUri(tenant, "authorize");

        StringBuilder sb = new StringBuilder(baseUri.toString());
        sb.append("?client_id=").append(urlEncode(clientId));
        sb.append("&response_type=code");
        sb.append("&redirect_uri=").append(urlEncode(effectiveRedirect));
        sb.append("&scope=").append(urlEncode(scope));
        sb.append("&code_challenge=").append(urlEncode(challenge.codeChallenge()));
        sb.append("&code_challenge_method=S256");
        sb.append("&state=").append(urlEncode(challenge.state()));
        sb.append("&prompt=select_account");

        if (loginHint != null && !loginHint.isBlank()) {
            sb.append("&login_hint=").append(urlEncode(loginHint));
        }

        return URI.create(sb.toString());
    }

    /**
     * Exchanges an authorization code and PKCE verifier for OAuth tokens with the default nativeclient redirect.
     */
    public AuthResult exchangeCodeForTokens(String tenant, String authorizationCode, String codeVerifier) {
        return exchangeCodeForTokens(tenant, authorizationCode, codeVerifier, REDIRECT_URI);
    }

    /**
     * Exchanges an authorization code and PKCE verifier for OAuth tokens with a specific redirect URI.
     */
    public AuthResult exchangeCodeForTokens(String tenant, String authorizationCode, String codeVerifier, String redirectUri) {
        String effectiveRedirect = (redirectUri != null && !redirectUri.isBlank()) ? redirectUri : REDIRECT_URI;
        URI tokenUri = buildEndpointUri(tenant, "token");

        Map<String, String> params = new HashMap<>();
        params.put("client_id", clientId);
        params.put("grant_type", "authorization_code");
        params.put("code", authorizationCode);
        params.put("redirect_uri", effectiveRedirect);
        params.put("code_verifier", codeVerifier);
        params.put("scope", scope);

        return executeTokenRequest(tokenUri, params);
    }

    /**
     * Performs a silent token refresh using a cached refresh token.
     */
    public AuthResult refreshToken(String tenant, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return new AuthResult.Failure("missing_refresh_token", "No refresh token available");
        }

        URI tokenUri = buildEndpointUri(tenant, "token");

        Map<String, String> params = new HashMap<>();
        params.put("client_id", clientId);
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        params.put("scope", scope);

        return executeTokenRequest(tokenUri, params);
    }

    /**
     * Initiates the Device Code authorization flow.
     */
    public DeviceCodeResponse requestDeviceCode(String tenant) throws IOException, InterruptedException {
        URI deviceCodeUri = buildEndpointUri(tenant, "devicecode");

        Map<String, String> params = new HashMap<>();
        params.put("client_id", clientId);
        params.put("scope", scope);

        String formBody = encodeFormData(params);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(deviceCodeUri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
            .timeout(Duration.ofSeconds(15))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Device code request failed with HTTP " + response.statusCode() + ": " + response.body());
        }

        return mapper.readValue(response.body(), DeviceCodeResponse.class);
    }

    /**
     * Polls the token endpoint for device code authorization until completed, expired, or failed.
     */
    public AuthResult pollDeviceCode(
        String tenant,
        String deviceCode,
        int intervalSec,
        int timeoutSec,
        Consumer<String> statusListener
    ) throws InterruptedException {
        URI tokenUri = buildEndpointUri(tenant, "token");

        int pollInterval = Math.max(intervalSec, 3);
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSec);

        Map<String, String> params = new HashMap<>();
        params.put("client_id", clientId);
        params.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        params.put("device_code", deviceCode);

        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Device code polling was interrupted");
            }

            try {
                String formBody = encodeFormData(params);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(tokenUri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(15))
                    .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    TokenResponse tokens = mapper.readValue(response.body(), TokenResponse.class);
                    UserClaims claims = JwtClaimsParser.parseIdToken(tokens.idToken());
                    return new AuthResult.Success(tokens, claims);
                }

                JsonNode errorNode = mapper.readTree(response.body());
                String error = errorNode.has("error") ? errorNode.get("error").asText() : "unknown_error";
                String errorDesc = errorNode.has("error_description") ? errorNode.get("error_description").asText() : "";

                switch (error) {
                    case "authorization_pending" -> {
                        if (statusListener != null) {
                            statusListener.accept("Waiting for user to authenticate...");
                        }
                    }
                    case "slow_down" -> {
                        pollInterval += 5;
                        if (statusListener != null) {
                            statusListener.accept("Rate limit hit, slowing down polling interval...");
                        }
                    }
                    case "expired_token" -> {
                        return new AuthResult.Failure("expired_token", "Device code expired. Please try signing in again.");
                    }
                    default -> {
                        return new AuthResult.Failure(error, errorDesc.isBlank() ? "Authentication failed: " + error : errorDesc);
                    }
                }
            } catch (IOException e) {
                if (statusListener != null) {
                    statusListener.accept("Network error during token poll: " + e.getMessage());
                }
            }

            TimeUnit.SECONDS.sleep(pollInterval);
        }

        return new AuthResult.Failure("timeout", "Sign-in timed out. Please try again.");
    }

    private static URI buildEndpointUri(String tenant, String endpoint) {
        String effectiveTenant = (tenant != null && !tenant.isBlank()) ? tenant : DEFAULT_TENANT;
        if (effectiveTenant.startsWith("http://") || effectiveTenant.startsWith("https://")) {
            return URI.create(effectiveTenant + "/oauth2/v2.0/" + endpoint);
        }
        return URI.create(String.format(LOGIN_BASE, effectiveTenant) + "/" + endpoint);
    }

    private AuthResult executeTokenRequest(URI tokenUri, Map<String, String> params) {
        try {
            String formBody = encodeFormData(params);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(tokenUri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(20))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                TokenResponse tokens = mapper.readValue(response.body(), TokenResponse.class);
                UserClaims claims = JwtClaimsParser.parseIdToken(tokens.idToken());
                return new AuthResult.Success(tokens, claims);
            }

            JsonNode errorNode = mapper.readTree(response.body());
            String error = errorNode.has("error") ? errorNode.get("error").asText() : "http_" + response.statusCode();
            String errorDesc = errorNode.has("error_description") ? errorNode.get("error_description").asText() : response.body();

            return new AuthResult.Failure(error, errorDesc);
        } catch (Exception e) {
            return new AuthResult.Failure("network_error", "Failed to communicate with identity provider: " + e.getMessage(), e);
        }
    }

    private static String encodeFormData(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value != null ? value : "", StandardCharsets.UTF_8);
    }
}
