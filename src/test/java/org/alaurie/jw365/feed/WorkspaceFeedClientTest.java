package org.alaurie.jw365.feed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceFeedClientTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicBoolean authHeaderReceived = new AtomicBoolean(false);
    private static final AtomicBoolean redirectAuthHeaderReceived = new AtomicBoolean(false);
    private static final AtomicBoolean userAgentHeaderReceived = new AtomicBoolean(false);

    private static final String MOCK_DISCOVERY_XML = """
        <?xml version="1.0" encoding="utf-8"?>
        <TenantFeedURLs xmlns="http://schemas.microsoft.com/ts/2014/03/tswfdiscovery">
          <TenantFeedURL
            TenantId="tenant-123"
            TenantDisplayName="Contoso Workspace"
            FeedURL="http://localhost:PORT/api/feed/tenant-123" />
        </TenantFeedURLs>
        """;

    private static final String MOCK_WORKSPACE_XML = """
        <?xml version="1.0" encoding="utf-8"?>
        <ResourceCollection xmlns="http://schemas.microsoft.com/ts/2007/05/tswf">
          <Publisher Name="Contoso Enterprise" ID="tenant-123">
            <Resources>
              <Resource ID="res-cloudpc-1" Title="Cloud PC 01" Type="Desktop">
                <HostingTerminalServers>
                  <HostingTerminalServer>
                    <ResourceFile FileExtension=".rdp" URL="http://localhost:PORT/api/rdp/cloudpc1.rdp" />
                  </HostingTerminalServer>
                </HostingTerminalServers>
                <Icons>
                  <Icon32 FileURL="http://localhost:PORT/api/icons/cloudpc1.png" />
                </Icons>
              </Resource>
            </Resources>
          </Publisher>
        </ResourceCollection>
        """;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // 1. Discovery endpoint
        server.createContext("/api/arm/feeddiscovery", exchange -> {
            validateHeaders(exchange);
            byte[] body = ("\uFEFF" + MOCK_DISCOVERY_XML.replace("PORT", String.valueOf(port))).getBytes(StandardCharsets.UTF_8);
            sendResponse(exchange, 200, "application/x-msts-radc-discovery+xml; charset=utf-8", body);
        });

        // 2. Feed endpoint
        server.createContext("/api/feed/tenant-123", exchange -> {
            validateHeaders(exchange);
            byte[] body = ("\uFEFF" + MOCK_WORKSPACE_XML.replace("PORT", String.valueOf(port))).getBytes(StandardCharsets.UTF_8);
            sendResponse(exchange, 200, "application/x-msts-radc+xml; charset=utf-8", body);
        });

        // 3. RDP file download endpoint
        server.createContext("/api/rdp/cloudpc1.rdp", exchange -> {
            byte[] rdpContent = "full address:s:cloudpc.wvd.microsoft.com\ngatewayhostname:s:gateway.wvd.microsoft.com".getBytes(StandardCharsets.UTF_8);
            sendResponse(exchange, 200, "application/x-rdp", rdpContent);
        });

        // 4. Icon endpoint
        server.createContext("/api/icons/cloudpc1.png", exchange -> {
            byte[] pngBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
            sendResponse(exchange, 200, "image/png", pngBytes);
        });
        server.createContext("/api/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + port + "/api/redirect-target");
            sendResponse(exchange, 302, "text/plain", new byte[0]);
        });
        server.createContext("/api/redirect-target", exchange -> {
            if (exchange.getRequestHeaders().getFirst("Authorization") != null) {
                redirectAuthHeaderReceived.set(true);
            }
            sendResponse(exchange, 200, "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        });

        // 5. Error endpoint
        server.createContext("/api/error/unauthorized", exchange -> sendResponse(exchange, 401, "application/json", "{\"error\": \"Unauthorized\"}".getBytes(StandardCharsets.UTF_8)));

        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void validateHeaders(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer test_token")) {
            authHeaderReceived.set(true);
        }
        String ua = exchange.getRequestHeaders().getFirst("User-Agent");
        String xmsUa = exchange.getRequestHeaders().getFirst("X-MS-User-Agent");
        if (WorkspaceFeedClient.USER_AGENT.equals(ua) && WorkspaceFeedClient.USER_AGENT.equals(xmsUa)) {
            userAgentHeaderReceived.set(true);
        }
    }

    private static void sendResponse(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (exchange; OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    @Test
    @DisplayName("WorkspaceFeedClient does not send bearer credentials to localhost")
    void testDiscoverTenantFeeds() throws Exception {
        URI discoveryUri = URI.create("http://localhost:" + port + "/api/arm/feeddiscovery");
        WorkspaceFeedClient client = new WorkspaceFeedClient(discoveryUri, HttpClient.newHttpClient(), WorkspaceFeedClient.localTestEndpointPolicy());

        List<TenantFeed> feeds = client.discoverTenantFeeds("test_token_xyz");

        assertThat(feeds).hasSize(1);
        TenantFeed feed = feeds.getFirst();
        assertThat(feed.tenantId()).isEqualTo("tenant-123");
        assertThat(feed.tenantDisplayName()).isEqualTo("Contoso Workspace");
        assertThat(feed.feedUrl().toString()).contains("http://localhost:" + port + "/api/feed/tenant-123");

        assertThat(authHeaderReceived.get()).isFalse();
        assertThat(userAgentHeaderReceived.get()).isTrue();
    }

    @Test
    @DisplayName("WorkspaceFeedClient fetches workspace resources and handles concurrent virtual thread fan-out")
    void testFetchAllWorkspaces() {
        URI discoveryUri = URI.create("http://localhost:" + port + "/api/arm/feeddiscovery");
        WorkspaceFeedClient client = new WorkspaceFeedClient(discoveryUri, HttpClient.newHttpClient(), WorkspaceFeedClient.localTestEndpointPolicy());

        TenantFeed tenant = new TenantFeed("tenant-123", "Contoso Workspace", URI.create("http://localhost:" + port + "/api/feed/tenant-123"));
        TenantFeed failedTenant = new TenantFeed("tenant-failed", "Failed Workspace", URI.create("http://localhost:" + port + "/api/error/unauthorized"));

        List<Workspace> workspaces = client.fetchAllWorkspaces("test_token_xyz", List.of(tenant, failedTenant));

        assertThat(workspaces).hasSize(1);
        Workspace ws = workspaces.getFirst();
        assertThat(ws.tenantId()).isEqualTo("tenant-123");
        assertThat(ws.resources()).hasSize(1);

        WorkspaceResource res = ws.resources().getFirst();
        assertThat(res.id()).isEqualTo("res-cloudpc-1");
        assertThat(res.title()).isEqualTo("Cloud PC 01");
        assertThat(res.type()).isEqualTo(ResourceType.DESKTOP);
    }

    @Test
    @DisplayName("WorkspaceFeedClient downloads RDP files and icons atomically")
    void testDownloads(@TempDir Path tempDir) throws Exception {
        URI discoveryUri = URI.create("http://localhost:" + port + "/api/arm/feeddiscovery");
        WorkspaceFeedClient client = new WorkspaceFeedClient(discoveryUri, HttpClient.newHttpClient(), WorkspaceFeedClient.localTestEndpointPolicy());

        Path targetRdp = tempDir.resolve("test-download.rdp");
        URI rdpUri = URI.create("http://localhost:" + port + "/api/rdp/cloudpc1.rdp");

        client.downloadRdpFile("test_token_xyz", rdpUri, targetRdp);

        assertThat(Files.exists(targetRdp)).isTrue();
        String content = Files.readString(targetRdp);
        assertThat(content).contains("gatewayhostname:s:gateway.wvd.microsoft.com");
        assertThat(content).doesNotContain("use multimon:i:1");

        URI iconUri = URI.create("http://localhost:" + port + "/api/icons/cloudpc1.png");
        byte[] iconBytes = client.downloadIconBytes("test_token_xyz", iconUri);
        assertThat(iconBytes).isNotNull().startsWith(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
    }
    @Test
    @DisplayName("WorkspaceFeedClient rejects hostile icon hosts and strips bearer on cross-origin redirects")
    void rejectsHostileIconAndRedirect() {
        URI discoveryUri = URI.create("http://localhost:" + port + "/api/arm/feeddiscovery");
        WorkspaceFeedClient client = new WorkspaceFeedClient(discoveryUri, HttpClient.newHttpClient(), WorkspaceFeedClient.localTestEndpointPolicy());

        assertThat(client.downloadIconBytes("test_token_xyz", URI.create("https://attacker.example/icon.png"))).isNull();
        byte[] redirected = client.downloadIconBytes("test_token_xyz", URI.create("http://localhost:" + port + "/api/redirect"));

        assertThat(redirected).isNotNull();
        assertThat(redirectAuthHeaderReceived).isFalse();
    }


    @Test
    @DisplayName("WorkspaceFeedClient throws IOException on HTTP error response")
    void testHttpError() {
        URI errorUri = URI.create("http://localhost:" + port + "/api/error/unauthorized");
        WorkspaceFeedClient client = new WorkspaceFeedClient(errorUri, HttpClient.newHttpClient(), WorkspaceFeedClient.localTestEndpointPolicy());

        assertThatThrownBy(() -> client.discoverTenantFeeds("invalid_token"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("401");
    }
}
