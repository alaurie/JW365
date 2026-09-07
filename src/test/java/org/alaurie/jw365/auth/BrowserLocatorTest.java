package org.alaurie.jw365.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserLocatorTest {

    @Test
    @DisplayName("BrowserLocator discovers available browsers and detects Edge")
    void testBrowserLocator() {
        List<BrowserInfo> browsers = BrowserLocator.findAllInstalled();
        assertThat(browsers).isNotEmpty();

        BrowserInfo best = BrowserLocator.findBestBrowser();
        assertThat(best).isNotNull();

        Optional<BrowserInfo> edge = BrowserLocator.findEdge();
        if (edge.isPresent()) {
            assertThat(edge.get().isEdge()).isTrue();
            assertThat(edge.get().displayName()).contains("Microsoft Edge");
            assertThat(best.isEdge()).isTrue();
        }
    }

    @Test
    @DisplayName("LoopbackAuthReceiver captures authorization code via HTTP callback")
    void testLoopbackAuthReceiver() throws Exception {
        try (LoopbackAuthReceiver receiver = new LoopbackAuthReceiver()) {
            int port = receiver.getPort();
            assertThat(port).isGreaterThan(0);

            URI redirectUri = receiver.getRedirectUri();
            assertThat(redirectUri.getHost()).isEqualTo("localhost");
            assertThat(redirectUri.getPort()).isEqualTo(port);
            assertThat(redirectUri.getPath()).isEqualTo("/auth/callback");

            CompletableFuture<String> codeFuture = receiver.waitForAuthCode(Duration.ofSeconds(10));

            // Simulate browser redirect with auth code
            URI callbackRequest = URI.create("http://127.0.0.1:" + port + "/auth/callback?code=mock_auth_code_12345&state=xyz");
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(callbackRequest).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("Sign-in Successful");

            String receivedCode = codeFuture.get();
            assertThat(receivedCode).isEqualTo("mock_auth_code_12345");
        }
    }
}
