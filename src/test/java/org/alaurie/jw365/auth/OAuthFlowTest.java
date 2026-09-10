package org.alaurie.jw365.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthFlowTest {

    private static HttpServer server;
    private static int port;

    private static final String SAMPLE_ID_TOKEN = "eyJhbGciOiJSUzI1NiJ9." +
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"upn\":\"test.user@contoso.com\",\"name\":\"Test User\",\"tid\":\"00000000-0000-0000-0000-000000000001\"}".getBytes(StandardCharsets.UTF_8)
        ) + ".sig";

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/mocktenant/oauth2/v2.0/token", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String form = new String(body, StandardCharsets.UTF_8);

            if (form.contains("grant_type=authorization_code") && form.contains("code=valid_code")) {
                String resp = """
                    {
                      "access_token": "mock_access_token_123",
                      "refresh_token": "mock_refresh_token_456",
                      "id_token": "%s",
                      "token_type": "Bearer",
                      "expires_in": 3600,
                      "scope": "https://www.wvd.microsoft.com/.default"
                    }
                    """.formatted(SAMPLE_ID_TOKEN);
                sendResponse(exchange, 200, resp);
            } else if (form.contains("grant_type=refresh_token") && form.contains("refresh_token=valid_rt")) {
                String resp = """
                    {
                      "access_token": "mock_renewed_access_token",
                      "refresh_token": "mock_rotated_refresh_token",
                      "id_token": "%s",
                      "token_type": "Bearer",
                      "expires_in": 3600,
                      "scope": "https://www.wvd.microsoft.com/.default"
                    }
                    """.formatted(SAMPLE_ID_TOKEN);
                sendResponse(exchange, 200, resp);
            } else {
                String err = """
                    {
                      "error": "invalid_grant",
                      "error_description": "The authorization code has expired or is invalid."
                    }
                    """;
                sendResponse(exchange, 400, err);
            }
        });

        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void sendResponse(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (exchange; OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    @DisplayName("OAuthClient successfully exchanges authorization code and parses tokens and claims")
    void testCodeExchange() {
        // Use custom client pointing to mock server
        OAuthClient client = new OAuthClient("mock_client_id", "mock_scope", HttpClient.newHttpClient());
        String tenantOverride = "http://localhost:" + port + "/mocktenant";

        AuthResult result = client.exchangeCodeForTokens(tenantOverride, "valid_code", "mock_verifier", OAuthClient.REDIRECT_URI);

        assertThat(result).isInstanceOf(AuthResult.Success.class);
        AuthResult.Success success = (AuthResult.Success) result;

        assertThat(success.tokens().accessToken()).isEqualTo("mock_access_token_123");
        assertThat(success.tokens().refreshToken()).isEqualTo("mock_refresh_token_456");
        assertThat(success.claims().upn()).isEqualTo("test.user@contoso.com");
        assertThat(success.claims().name()).isEqualTo("Test User");
    }

    @Test
    @DisplayName("OAuthClient successfully refreshes token and rotates refresh token")
    void testRefreshToken() {
        OAuthClient client = new OAuthClient("mock_client_id", "mock_scope", HttpClient.newHttpClient());
        String tenantOverride = "http://localhost:" + port + "/mocktenant";

        AuthResult result = client.refreshToken(tenantOverride, "valid_rt");

        assertThat(result).isInstanceOf(AuthResult.Success.class);
        AuthResult.Success success = (AuthResult.Success) result;

        assertThat(success.tokens().accessToken()).isEqualTo("mock_renewed_access_token");
        assertThat(success.tokens().refreshToken()).isEqualTo("mock_rotated_refresh_token");
    }

    @Test
    @DisplayName("OAuthClient handles error responses with error code and description")
    void testErrorHandling() {
        OAuthClient client = new OAuthClient("mock_client_id", "mock_scope", HttpClient.newHttpClient());
        String tenantOverride = "http://localhost:" + port + "/mocktenant";

        AuthResult result = client.exchangeCodeForTokens(tenantOverride, "invalid_code", "mock_verifier");

        assertThat(result).isInstanceOf(AuthResult.Failure.class);
        AuthResult.Failure failure = (AuthResult.Failure) result;

        assertThat(failure.errorCode()).isEqualTo("invalid_grant");
        assertThat(failure.errorMessage()).contains("expired or is invalid");
    }
}
