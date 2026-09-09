package org.alaurie.jw365.feed;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Modern HTTP/2 client for Windows 365 and AVD Workspace Feed Discovery and Resource APIs.
 */
public final class WorkspaceFeedClient {

    public static final String DEFAULT_DISCOVERY_URL = "https://rdweb.wvd.microsoft.com/api/arm/feeddiscovery";
    public static final String USER_AGENT = "com.microsoft.rdc.html/2.0.79.2 rdhtml-sdk/2.0.4";
    private static final int MAX_XML_RESPONSE_BYTES = 10 * 1024 * 1024;

    private final URI discoveryUri;
    private final HttpClient httpClient;

    public WorkspaceFeedClient() {
        this(URI.create(DEFAULT_DISCOVERY_URL), HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
    }

    public WorkspaceFeedClient(URI discoveryUri, HttpClient httpClient) {
        this.discoveryUri = Objects.requireNonNull(discoveryUri, "discoveryUri must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    /**
     * Discovers all available tenant feed endpoints for the authenticated user.
     *
     * @param accessToken Entra ID OAuth 2.0 access token
     * @return list of TenantFeed endpoints
     */
    public List<TenantFeed> discoverTenantFeeds(String accessToken) throws IOException, InterruptedException {
        Objects.requireNonNull(accessToken, "accessToken must not be null");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(discoveryUri)
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/x-msts-radc-discovery+xml,text/xml")
            .header("User-Agent", USER_AGENT)
            .header("X-MS-User-Agent", USER_AGENT)
            .GET()
            .timeout(Duration.ofSeconds(25))
            .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String body = responseBody(response);
        if (response.statusCode() != 200) {
            throw new IOException("Workspace feed discovery failed with HTTP " + response.statusCode() + ": " + body);
        }

        return WorkspaceFeedParser.parseDiscoveryXml(body);
    }

    /**
     * Fetches the workspace feed for a specific tenant.
     *
     * @param accessToken Entra ID OAuth 2.0 access token
     * @param tenantFeed  the TenantFeed to query
     * @return parsed Workspace
     */
    public Workspace fetchTenantWorkspace(String accessToken, TenantFeed tenantFeed) throws IOException, InterruptedException {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(tenantFeed, "tenantFeed must not be null");

        HttpRequest request = HttpRequest.newBuilder()
            .uri(tenantFeed.feedUrl())
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/x-msts-radc+xml;radc_schema_version=2.0,text/xml")
            .header("User-Agent", USER_AGENT)
            .header("X-MS-User-Agent", USER_AGENT)
            .GET()
            .timeout(Duration.ofSeconds(25))
            .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String body = responseBody(response);
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch feed for tenant " + tenantFeed.tenantDisplayName() + " (HTTP " + response.statusCode() + "): " + body);
        }

        return WorkspaceFeedParser.parseFeedXml(body, tenantFeed);
    }

    /**
     * Fetches all workspaces concurrently using Java Virtual Threads.
     *
     * @param accessToken Entra ID access token
     * @param tenantFeeds list of tenant feeds to query
     * @return list of populated Workspace instances
     */
    public List<Workspace> fetchAllWorkspaces(String accessToken, List<TenantFeed> tenantFeeds) {
        if (tenantFeeds == null || tenantFeeds.isEmpty()) {
            return Collections.emptyList();
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Workspace>> tasks = tenantFeeds.stream()
                .map(feed -> (Callable<Workspace>) () -> fetchTenantWorkspace(accessToken, feed))
                .toList();

            List<Future<Workspace>> futures = executor.invokeAll(tasks);
            List<Workspace> workspaces = new ArrayList<>();
            boolean failed = false;
            for (Future<Workspace> f : futures) {
                try {
                    workspaces.add(f.get());
                } catch (Exception e) {
                    failed = true;
                    System.err.println("Warning: Failed to fetch workspace feed: " + e.getMessage());
                }
            }
            if (failed) {
                throw new IllegalStateException("One or more workspace feeds failed");
            }
            return workspaces;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workspace feed fetch interrupted", e);
        }
    }

    /**
     * Downloads an RDP configuration file from a resource's RDP URL.
     *
     * @param accessToken Entra ID access token
     * @param rdpUrl      URI of the RDP file
     * @param targetPath  destination file path
     */
    public void downloadRdpFile(String accessToken, URI rdpUrl, Path targetPath) throws IOException, InterruptedException {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(rdpUrl, "rdpUrl must not be null");
        Objects.requireNonNull(targetPath, "targetPath must not be null");

        Path parent = targetPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(rdpUrl)
            .header("Authorization", "Bearer " + accessToken)
            .header("User-Agent", USER_AGENT)
            .header("X-MS-User-Agent", USER_AGENT)
            .GET()
            .timeout(Duration.ofSeconds(30))
            .build();

        Path tempFile = targetPath.resolveSibling(targetPath.getFileName() + ".tmp." + System.nanoTime());
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));

        if (response.statusCode() != 200) {
            Files.deleteIfExists(tempFile);
            throw new IOException("Failed to download RDP file from " + rdpUrl + " (HTTP " + response.statusCode() + ")");
        }

        Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Downloads raw PNG icon bytes from the icon URL.
     *
     * @param accessToken Entra ID access token
     * @param iconUrl     URI of the icon
     * @return raw image bytes, or null if download fails
     */
    public byte[] downloadIconBytes(String accessToken, URI iconUrl) {
        if (iconUrl == null) {
            return null;
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(iconUrl)
                .header("User-Agent", USER_AGENT)
                .header("X-MS-User-Agent", USER_AGENT)
                .GET()
                .timeout(Duration.ofSeconds(15));

            if (accessToken != null && !accessToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + accessToken);
            }

            HttpResponse<byte[]> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return response.body();
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to download icon from " + iconUrl + ": " + e.getMessage());
        }
        return null;
    }

    private static String responseBody(HttpResponse<byte[]> response) throws IOException {
        if (response.body().length > MAX_XML_RESPONSE_BYTES) {
            throw new IOException("Workspace feed response exceeds 10 MiB limit");
        }
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
