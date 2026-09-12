package org.alaurie.jw365.auth;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.alaurie.jw365.config.XdgPaths;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
/** Accepts WebView session cookies for the sign-in flow; only Microsoft cookies are persisted. */
public final class PersistentCookieManager extends CookieManager {
    private static final ObjectMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final Path storageFile;
    private final AtomicBoolean autoPersistPending = new AtomicBoolean(false);
    public PersistentCookieManager() { this(XdgPaths.dataDir().resolve("webview-cookies.enc")); }

    public PersistentCookieManager(Path storageFile) {
        super(null, CookiePolicy.ACCEPT_ALL);
        this.storageFile = storageFile;
        loadCookies();
    }


    private static String normalizeDomain(String domain) { return domain == null ? "" : domain.toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""); }

    static boolean isMicrosoftDomain(String host) {
        if (host == null || host.isBlank()) return false;
        String normalized = normalizeDomain(host);
        return normalized.equals("microsoftonline.com") || normalized.endsWith(".microsoftonline.com")
            || normalized.equals("microsoft.com") || normalized.endsWith(".microsoft.com")
            || normalized.equals("live.com") || normalized.endsWith(".live.com")
            || normalized.equals("msftauth.net") || normalized.endsWith(".msftauth.net")
            || normalized.equals("windowsazure.com") || normalized.endsWith(".windowsazure.com");
    }

    public synchronized void loadCookies() {
        if (!Files.exists(storageFile)) return;
        try {
            byte[] encrypted = Files.readAllBytes(storageFile);
            if (encrypted.length == 0) return;
            byte[] json = MachineBoundCrypto.decrypt(encrypted);
            List<SerializableCookie> saved = MAPPER.readValue(json, new TypeReference<>() {});
            CookieStore store = getCookieStore();
            for (SerializableCookie sc : saved) {
                if (sc.hasExpired() || !isMicrosoftDomain(sc.domain())) continue;
                URI uri = sc.uri() == null ? URI.create("https://" + normalizeDomain(sc.domain())) : URI.create(sc.uri());
                if (!isMicrosoftDomain(uri.getHost()) || !"https".equalsIgnoreCase(uri.getScheme())) continue;
                HttpCookie cookie = new HttpCookie(sc.name(), sc.value());
                cookie.setDomain(sc.domain());
                cookie.setPath(sc.path() != null ? sc.path() : "/");
                cookie.setSecure(sc.secure());
                cookie.setHttpOnly(sc.httpOnly());
                if (sc.maxAge() > 0) {
                    long remaining = sc.maxAge() - (System.currentTimeMillis() - sc.savedAt()) / 1000;
                    if (remaining <= 0) continue;
                    cookie.setMaxAge(remaining);
                } else {
                    cookie.setMaxAge(-1);
                }
                store.add(uri, cookie);
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not load persistent cookies: " + e.getMessage());
        }
    }

    public synchronized void persistCookies() {
        try {
            Map<String, SerializableCookie> cookieMap = new LinkedHashMap<>();
            CookieStore store = getCookieStore();
            long now = System.currentTimeMillis();

            // 1. Process all cookies directly in store
            for (HttpCookie cookie : store.getCookies()) {
                if (cookie.hasExpired()) continue;
                String domain = cookie.getDomain();
                if (domain != null && isMicrosoftDomain(domain)) {
                    String normDomain = normalizeDomain(domain);
                    String path = cookie.getPath() != null ? cookie.getPath() : "/";
                    String key = cookie.getName() + "|" + normDomain + "|" + path;
                    cookieMap.put(key, new SerializableCookie(
                        cookie.getName(), cookie.getValue(), domain, path,
                        cookie.getSecure(), cookie.isHttpOnly(), cookie.getMaxAge(), now, "https://" + normDomain
                    ));
                }
            }

            // 2. Process cookies indexed by URI (including host-only cookies where domain might not be explicitly set)
            for (URI uri : store.getURIs()) {
                String host = uri.getHost();
                if (!isMicrosoftDomain(host)) continue;
                URI httpsUri = URI.create("https://" + host);
                for (HttpCookie cookie : store.get(httpsUri)) {
                    if (cookie.hasExpired()) continue;
                    String domain = cookie.getDomain() != null ? cookie.getDomain() : host;
                    if (!isMicrosoftDomain(domain)) continue;
                    String normDomain = normalizeDomain(domain);
                    String path = cookie.getPath() != null ? cookie.getPath() : "/";
                    String key = cookie.getName() + "|" + normDomain + "|" + path;
                    cookieMap.putIfAbsent(key, new SerializableCookie(
                        cookie.getName(), cookie.getValue(), domain, path,
                        cookie.getSecure(), cookie.isHttpOnly(), cookie.getMaxAge(), now, "https://" + normDomain
                    ));
                }
            }

            List<SerializableCookie> list = new ArrayList<>(cookieMap.values());
            Path parent = storageFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                try { Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------")); } catch (Exception _) {}
            }
            Path temp = storageFile.resolveSibling(storageFile.getFileName() + ".tmp." + System.nanoTime());
            try {
                byte[] encrypted = MachineBoundCrypto.encrypt(MAPPER.writeValueAsBytes(list));
                Files.write(temp, encrypted);
                try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); } catch (Exception _) {}
                try {
                    Files.move(temp, storageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, storageFile, StandardCopyOption.REPLACE_EXISTING);
                }
                try { Files.setPosixFilePermissions(storageFile, PosixFilePermissions.fromString("rw-------")); } catch (Exception _) {}
            } finally { Files.deleteIfExists(temp); }
        } catch (Exception e) {
            System.err.println("Warning: Could not persist cookies: " + e.getMessage());
        }
    }

    @Override
    public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
        super.put(uri, responseHeaders);
        if (uri != null && isMicrosoftDomain(uri.getHost()) && responseHeaders != null) {
            boolean hasSetCookie = responseHeaders.keySet().stream()
                .anyMatch(h -> "Set-Cookie".equalsIgnoreCase(h));
            if (hasSetCookie && autoPersistPending.compareAndSet(false, true)) {
                Thread.ofVirtual().name("cookie-auto-persist").start(() -> {
                    try {
                        Thread.sleep(Duration.ofMillis(300));
                    } catch (InterruptedException _) {
                        Thread.currentThread().interrupt();
                    } finally {
                        autoPersistPending.set(false);
                        persistCookies();
                    }
                });
            }
        }
    }

    public record SerializableCookie(String name, String value, String domain, String path, boolean secure,
                                     boolean httpOnly, long maxAge, long savedAt, String uri) {
        public boolean hasExpired() {
            if (maxAge > 0) {
                return (System.currentTimeMillis() - savedAt) / 1000 >= maxAge;
            }
            // Session cookies without maxAge (maxAge <= 0) remain valid for up to 24 hours from save
            return (System.currentTimeMillis() - savedAt) > 24 * 3600 * 1000L;
        }
    }
}
