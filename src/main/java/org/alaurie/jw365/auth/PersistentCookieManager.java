package org.alaurie.jw365.auth;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import org.alaurie.jw365.config.XdgPaths;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent CookieManager that saves and restores session cookies for JavaFX WebEngine,
 * allowing Microsoft M365 SSO and session persistence across app launches.
 */
public final class PersistentCookieManager extends CookieManager {

    private static final ObjectMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private final Path storageFile;

    public PersistentCookieManager() {
        this(XdgPaths.dataDir().resolve("webview-cookies.json"));
    }

    public PersistentCookieManager(Path storageFile) {
        super(null, CookiePolicy.ACCEPT_ALL);
        this.storageFile = storageFile;
        loadCookies();
    }

    public void loadCookies() {
        if (!Files.exists(storageFile)) return;

        try {
            byte[] bytes = Files.readAllBytes(storageFile);
            if (bytes.length == 0) return;

            List<SerializableCookie> saved = MAPPER.readValue(bytes, new TypeReference<>() {});
            CookieStore store = getCookieStore();

            for (SerializableCookie sc : saved) {
                if (sc.hasExpired()) continue;
                HttpCookie c = new HttpCookie(sc.name(), sc.value());
                c.setDomain(sc.domain());
                c.setPath(sc.path());
                c.setSecure(sc.secure());
                c.setHttpOnly(sc.httpOnly());
                if (sc.maxAge() > 0) {
                    c.setMaxAge(sc.maxAge());
                }
                store.add(sc.uri() != null ? URI.create(sc.uri()) : null, c);
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not load persistent cookies: " + e.getMessage());
        }
    }

    public void persistCookies() {
        try {
            CookieStore store = getCookieStore();
            List<SerializableCookie> list = new ArrayList<>();

            for (HttpCookie c : store.getCookies()) {
                if (c.hasExpired()) continue;
                list.add(new SerializableCookie(
                    c.getName(),
                    c.getValue(),
                    c.getDomain(),
                    c.getPath(),
                    c.getSecure(),
                    c.isHttpOnly(),
                    c.getMaxAge(),
                    System.currentTimeMillis(),
                    null
                ));
            }

            Path parent = storageFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            MAPPER.writeValue(storageFile.toFile(), list);

            try {
                Files.setPosixFilePermissions(storageFile, PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not persist cookies: " + e.getMessage());
        }
    }

    public record SerializableCookie(
        String name,
        String value,
        String domain,
        String path,
        boolean secure,
        boolean httpOnly,
        long maxAge,
        long savedAt,
        String uri
    ) {
        public boolean hasExpired() {
            if (maxAge <= 0) return false; // session cookie
            long elapsedSeconds = (System.currentTimeMillis() - savedAt) / 1000;
            return elapsedSeconds >= maxAge;
        }
    }
}
