package org.alaurie.jw365.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentCookieManagerTest {

    @Test
    @DisplayName("PersistentCookieManager saves and restores valid cookies and drops expired ones")
    void testCookiePersistenceAndExpiration(@TempDir Path tempDir) {
        Path cookieFile = tempDir.resolve("test-cookies.json");

        PersistentCookieManager manager1 = new PersistentCookieManager(cookieFile);
        CookieStore store1 = manager1.getCookieStore();

        URI uri = URI.create("https://login.microsoftonline.com");

        // 1. Add an active session cookie (maxAge = 3600)
        HttpCookie activeCookie = new HttpCookie("ESTSAUTH", "mock_session_token_123");
        activeCookie.setDomain("login.microsoftonline.com");
        activeCookie.setPath("/");
        activeCookie.setSecure(true);
        activeCookie.setHttpOnly(true);
        activeCookie.setMaxAge(3600);
        store1.add(uri, activeCookie);

        // 2. Add an expired cookie (maxAge = -1 or expired)
        HttpCookie expiredCookie = new HttpCookie("OLD_SESSION", "expired_val");
        expiredCookie.setDomain("login.microsoftonline.com");
        expiredCookie.setPath("/");
        expiredCookie.setMaxAge(0); // already expired
        store1.add(uri, expiredCookie);

        // Save to disk
        manager1.persistCookies();

        assertThat(Files.exists(cookieFile)).isTrue();
        assertThat(cookieFile.toFile().length()).isGreaterThan(0);

        // Create fresh manager loading from the same file
        PersistentCookieManager manager2 = new PersistentCookieManager(cookieFile);
        CookieStore store2 = manager2.getCookieStore();

        List<HttpCookie> loadedCookies = store2.getCookies();
        assertThat(loadedCookies).hasSize(1);

        HttpCookie restored = loadedCookies.getFirst();
        assertThat(restored.getName()).isEqualTo("ESTSAUTH");
        assertThat(restored.getValue()).isEqualTo("mock_session_token_123");
        assertThat(restored.getDomain()).isEqualTo("login.microsoftonline.com");
        assertThat(restored.getSecure()).isTrue();
        assertThat(restored.isHttpOnly()).isTrue();
    }
}
