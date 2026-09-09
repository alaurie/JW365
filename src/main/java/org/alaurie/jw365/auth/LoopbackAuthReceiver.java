package org.alaurie.jw365.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight local HTTP loopback server that captures OAuth2 authorization code redirects from Edge or system browsers.
 */
public final class LoopbackAuthReceiver implements AutoCloseable {

    private static final Pattern CODE_PATTERN = Pattern.compile("(?:^|[?&])code=([^&]+)");
    private static final Pattern ERROR_PATTERN = Pattern.compile("(?:^|[?&])error=([^&]+)");

    private final HttpServer server;
    private final int port;
    private final CompletableFuture<String> codeFuture = new CompletableFuture<>();

    public LoopbackAuthReceiver() throws IOException {
        // Bind to 127.0.0.1 on ephemeral port (port 0 selects free port)
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.port = server.getAddress().getPort();
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        HttpHandler handler = this::handleRequest;
        this.server.createContext("/", handler);
        this.server.createContext("/auth/callback", handler);
        this.server.start();
    }

    public int getPort() {
        return port;
    }

    public URI getRedirectUri() {
        return URI.create("http://localhost:" + port + "/auth/callback");
    }

    /**
     * Waits for the browser to redirect with the authorization code.
     *
     * @param timeout maximum time to wait
     * @return the authorization code
     */
    public CompletableFuture<String> waitForAuthCode(Duration timeout) {
        CompletableFuture<String> timed = new CompletableFuture<>();

        codeFuture.orTimeout(timeout.toSeconds(), TimeUnit.SECONDS)
            .whenComplete((code, ex) -> {
                if (ex != null) {
                    timed.completeExceptionally(ex);
                } else {
                    timed.complete(code);
                }
                // Delay server shutdown slightly to ensure HTTP client cleanly finishes reading
                Thread.ofVirtual().start(() -> {
                    try {
                        TimeUnit.MILLISECONDS.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                    close();
                });
            });

        return timed;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        URI requestUri = exchange.getRequestURI();
        String query = requestUri.getRawQuery();

        String responseHtml;
        int responseCode;
        String extractedCode = null;
        String extractedError = null;

        if (query != null && query.contains("code=")) {
            Matcher m = CODE_PATTERN.matcher(query);
            if (m.find()) {
                extractedCode = decodeQueryValue(m.group(1));
                responseCode = 200;
                responseHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta charset="utf-8">
                        <title>JW365 - Sign-in Complete</title>
                        <style>
                            body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; background: #0f111a; color: #f8fafc; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }
                            .card { background: #181a26; border: 1px solid #31364f; border-radius: 12px; padding: 40px; text-align: center; max-width: 420px; box-shadow: 0 10px 30px rgba(0,0,0,0.5); }
                            h1 { color: #38bdf8; margin-top: 0; }
                            p { color: #94a3b8; font-size: 15px; }
                            .badge { display: inline-block; background: #10b98122; color: #34d399; padding: 4px 12px; border-radius: 16px; font-weight: bold; margin-bottom: 16px; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <div class="badge">&#10003; Authenticated</div>
                            <h1>Sign-in Successful</h1>
                            <p>You have signed in to Microsoft Entra ID for Windows 365.</p>
                            <p>You may now close this browser tab and return to <strong>JW365</strong>.</p>
                        </div>
                    </body>
                    </html>
                    """;
            } else {
                responseCode = 400;
                responseHtml = "<h1>Invalid Authorization Request</h1>";
            }
        } else if (query != null && query.contains("error=")) {
            Matcher m = ERROR_PATTERN.matcher(query);
            extractedError = m.find() ? decodeQueryValue(m.group(1)) : "unknown_error";
            responseCode = 400;
            responseHtml = "<h1>Sign-in Failed</h1><p>" + escapeHtml(extractedError) + "</p>";
        } else {
            responseCode = 200;
            responseHtml = "<h1>JW365 Authentication Endpoint</h1><p>Waiting for sign-in redirect...</p>";
        }

        byte[] bytes = responseHtml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(responseCode, bytes.length);

        try (exchange; OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
            os.flush();
        }

        if (extractedCode != null) {
            codeFuture.complete(extractedCode);
        } else if (extractedError != null) {
            codeFuture.completeExceptionally(new RuntimeException("OAuth Error: " + extractedError));
        }
    }

    @Override
    public void close() {
        try {
            server.stop(0);
        } catch (Exception ignored) {
        }
    }

    private static String decodeQueryValue(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
}
