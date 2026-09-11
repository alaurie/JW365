package org.alaurie.jw365.feed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * HTTP/2 client for Windows 365 and Azure Virtual Desktop workspace feeds.
 */
public final class WorkspaceFeedClient {

    @FunctionalInterface
    public interface EndpointPolicy {
        boolean isAllowed(URI uri);
    }

    public static EndpointPolicy microsoftEndpointPolicy() {
        return WorkspaceFeedClient::isMicrosoftEndpoint;
    }

    /** Explicit localhost policy for deterministic test servers; never used by production constructors. */
    public static EndpointPolicy localTestEndpointPolicy() {
        return uri -> isMicrosoftEndpoint(uri) || isLocalHttp(uri);
    }

    public static final String DEFAULT_DISCOVERY_URL = "https://rdweb.wvd.microsoft.com/api/arm/feeddiscovery";
    public static final String USER_AGENT = "com.microsoft.rdc.html/2.0.79.2 rdhtml-sdk/2.0.4";
    private static final int MAX_XML_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_RDP_RESPONSE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ICON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_TENANT_FEEDS = 32;
    private static final int MAX_FEED_CONCURRENCY = 4;
    private static final Duration ALL_FEEDS_TIMEOUT = Duration.ofSeconds(60);

    private final URI discoveryUri;
    private final HttpClient httpClient;
    private final EndpointPolicy endpointPolicy;

    public WorkspaceFeedClient() {
        this(URI.create(DEFAULT_DISCOVERY_URL), HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(), microsoftEndpointPolicy());
    }

    public WorkspaceFeedClient(URI discoveryUri, HttpClient httpClient) {
        this(discoveryUri, httpClient, microsoftEndpointPolicy());
    }

    public WorkspaceFeedClient(URI discoveryUri, HttpClient httpClient, EndpointPolicy endpointPolicy) {
        this.discoveryUri = Objects.requireNonNull(discoveryUri, "discoveryUri must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.endpointPolicy = Objects.requireNonNull(endpointPolicy, "endpointPolicy must not be null");
        if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("WorkspaceFeedClient requires redirects to be disabled");
        }
        requireAllowedEndpoint(discoveryUri);
    }

    /**
     * Discovers all available tenant feed endpoints for the authenticated user.
     *
     * @param accessToken Entra ID OAuth 2.0 access token
     * @return list of TenantFeed endpoints
     */
    public List<TenantFeed> discoverTenantFeeds(String accessToken) throws IOException, InterruptedException {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Response response = send(discoveryUri, accessToken, "application/x-msts-radc-discovery+xml,text/xml", MAX_XML_RESPONSE_BYTES);
        if (response.statusCode() != 200) {
            throw new IOException("Workspace feed discovery failed with HTTP " + response.statusCode() + ": " + response.text());
        }
        return WorkspaceFeedParser.parseDiscoveryXml(response.text(), endpointPolicy::isAllowed);
    }

    /**
     * Fetches the workspace feed for a specific tenant.
     */
    public Workspace fetchTenantWorkspace(String accessToken, TenantFeed tenantFeed) throws IOException, InterruptedException {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(tenantFeed, "tenantFeed must not be null");
        Response response = send(tenantFeed.feedUrl(), accessToken,
            "application/x-msts-radc+xml;radc_schema_version=2.0,text/xml", MAX_XML_RESPONSE_BYTES);
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch feed for tenant " + tenantFeed.tenantDisplayName()
                + " (HTTP " + response.statusCode() + "): " + response.text());
        }
        return WorkspaceFeedParser.parseFeedXml(response.text(), tenantFeed, endpointPolicy::isAllowed);
    }

    /**
     * Fetches all workspaces concurrently using Java Virtual Threads. A failed tenant does not discard
     * workspaces that were fetched successfully.
     */
    public List<Workspace> fetchAllWorkspaces(String accessToken, List<TenantFeed> tenantFeeds) {
        if (tenantFeeds == null || tenantFeeds.isEmpty()) return Collections.emptyList();
        if (tenantFeeds.size() > MAX_TENANT_FEEDS) {
            throw new IllegalArgumentException("Tenant feed count exceeds " + MAX_TENANT_FEEDS);
        }
        ExecutorService executor = Executors.newFixedThreadPool(MAX_FEED_CONCURRENCY, Thread.ofVirtual().factory());
        List<Future<Workspace>> futures = tenantFeeds.stream()
            .map(feed -> (Callable<Workspace>) () -> fetchTenantWorkspace(accessToken, feed))
            .map(executor::submit)
            .toList();
        try {
            List<Workspace> workspaces = new ArrayList<>();
            boolean failed = false;
            long deadline = System.nanoTime() + ALL_FEEDS_TIMEOUT.toNanos();
            for (Future<Workspace> future : futures) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new TimeoutException("aggregate feed timeout");
                    workspaces.add(future.get(remaining, TimeUnit.NANOSECONDS));
                } catch (InterruptedException e) {
                    futures.forEach(candidate -> candidate.cancel(true));
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Workspace feed fetch interrupted", e);
                } catch (TimeoutException e) {
                    futures.forEach(candidate -> candidate.cancel(true));
                    throw new IllegalStateException("Workspace feed fetch timed out", e);
                } catch (ExecutionException e) {
                    failed = true;
                    System.err.println("Warning: Failed to fetch workspace feed: " + e.getCause());
                }
            }
            if (workspaces.isEmpty() && failed) throw new IllegalStateException("All workspace feeds failed");
            return workspaces;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Downloads an RDP configuration file from a resource's RDP URL.
     */
    public void downloadRdpFile(String accessToken, URI rdpUrl, Path targetPath) throws IOException, InterruptedException {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(rdpUrl, "rdpUrl must not be null");
        Objects.requireNonNull(targetPath, "targetPath must not be null");
        Response response = send(rdpUrl, accessToken, null, MAX_RDP_RESPONSE_BYTES);
        if (response.statusCode() != 200) throw new IOException("Failed to download RDP file from " + rdpUrl + " (HTTP " + response.statusCode() + ")");
        Path parent = targetPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tempFile = targetPath.resolveSibling(targetPath.getFileName() + ".tmp." + System.nanoTime());
        try {
            Files.write(tempFile, response.body());
            try {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Downloads raw PNG icon bytes from the icon URL.
     */
    public byte[] downloadIconBytes(String accessToken, URI iconUrl) {
        if (iconUrl == null) {
            return null;
        }
        try {
            String token = (accessToken == null || accessToken.isBlank()) ? null : accessToken;
            Response response = send(iconUrl, token, null, MAX_ICON_BYTES);
            if (response.statusCode() == 200 && response.body().length > 0) {
                return response.body();
            }
            if (token != null && (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 400)) {
                Response retry = send(iconUrl, null, null, MAX_ICON_BYTES);
                if (retry.statusCode() == 200 && retry.body().length > 0) {
                    return retry.body();
                }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.println("Warning: Failed to download icon from " + iconUrl + ": " + e.getMessage());
            return null;
        }
    }
    private Response send(URI initialUri, String accessToken, String accept, int maxBytes) throws IOException, InterruptedException {
        requireAllowedEndpoint(initialUri);
        URI currentUri = initialUri;
        boolean crossedOrigin = false;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(currentUri)
                .header("User-Agent", USER_AGENT)
                .header("X-MS-User-Agent", USER_AGENT)
                .GET()
                .timeout(Duration.ofSeconds(30));
            if (accept != null) {
                builder.header("Accept", accept);
            }
            if (accessToken != null && !crossedOrigin && isSecureEndpoint(currentUri)) {
                builder.header("Authorization", "Bearer " + accessToken);
            }

            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) {
                body = readLimited(input, maxBytes);
            }
            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                return new Response(status, body);
            }
            String location = response.headers().firstValue("Location").orElse(null);
            if (location == null || redirect == MAX_REDIRECTS) {
                return new Response(status, body);
            }
            URI next = currentUri.resolve(location);
            requireAllowedEndpoint(next);
            crossedOrigin |= !sameOrigin(currentUri, next);
            currentUri = next;
        }
        throw new IOException("Too many redirects while requesting " + initialUri);
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > maxBytes - total) {
                throw new IOException("HTTP response exceeds " + maxBytes + " byte limit");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private void requireAllowedEndpoint(URI uri) {
        if (uri == null || uri.getUserInfo() != null || !endpointPolicy.isAllowed(uri)) {
            throw new IllegalArgumentException("Refusing non-Microsoft workspace endpoint: " + uri);
        }
    }

    private static boolean isMicrosoftEndpoint(URI uri) {
        if (uri == null || uri.getUserInfo() != null || uri.getHost() == null
            || !"https".equalsIgnoreCase(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        return host.equals("wvd.microsoft.com") || host.endsWith(".wvd.microsoft.com")
            || host.equals("microsoft.com") || host.endsWith(".microsoft.com")
            || host.equals("azure.com") || host.endsWith(".azure.com")
            || host.equals("windows.net") || host.endsWith(".windows.net")
            || host.equals("azureedge.net") || host.endsWith(".azureedge.net")
            || host.equals("office.com") || host.endsWith(".office.com")
            || host.equals("microsoftonline.com") || host.endsWith(".microsoftonline.com")
            || host.equals("msftauth.net") || host.endsWith(".msftauth.net")
            || host.equals("trafficmanager.net") || host.endsWith(".trafficmanager.net")
            || host.equals("wvd.azure.us") || host.endsWith(".wvd.azure.us")
            || host.equals("azure.us") || host.endsWith(".azure.us")
            || host.equals("wvd.azure.cn") || host.endsWith(".wvd.azure.cn")
            || host.equals("azure.cn") || host.endsWith(".azure.cn");
    }

    private static boolean isLocalHttp(URI uri) {
        if (uri == null || !"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
        String host = uri.getHost();
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
            && first.getHost().equalsIgnoreCase(second.getHost())
            && effectivePort(first) == effectivePort(second);
    }
    private static boolean isSecureEndpoint(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme())
            && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private record Response(int statusCode, byte[] body) {
        private String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
