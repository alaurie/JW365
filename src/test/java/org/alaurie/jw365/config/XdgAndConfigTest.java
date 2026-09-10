package org.alaurie.jw365.config;

import org.alaurie.jw365.auth.TokenResponse;
import org.alaurie.jw365.auth.TokenStore;
import org.alaurie.jw365.feed.ResourceType;
import org.alaurie.jw365.feed.Workspace;
import org.alaurie.jw365.feed.WorkspaceResource;
import org.alaurie.jw365.rdp.FreeRdpSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class XdgAndConfigTest {

    @Test
    @DisplayName("AppVersion loads non-empty application version string")
    void testAppVersionLoads() {
        assertThat(AppVersion.VERSION).isNotBlank();
        assertThat(AppVersion.VERSION).matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    @DisplayName("ConfigManager saves and loads ClientConfig JSON")
    void testConfigManagerSaveAndLoad(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.json");
        ConfigManager manager = new ConfigManager(configFile);

        ClientConfig initial = manager.get();
        assertThat(initial.defaultTenant()).isEqualTo("organizations");
        assertThat(initial.freerdpSource()).isEqualTo(FreeRdpSource.AUTO);
        assertThat(initial.autoConnect()).isFalse();

        ClientConfig custom = new ClientConfig(
            "custom-tenant-uuid",
            "/usr/bin/sdl-freerdp",
            150,
            true,
            true,
            false,
            true,
            true,
            30,
            List.of("/bpp:24")
        );

        manager.save(custom);

        ConfigManager reloadedManager = new ConfigManager(configFile);
        ClientConfig loaded = reloadedManager.load();

        assertThat(loaded.defaultTenant()).isEqualTo("custom-tenant-uuid");
        assertThat(loaded.freerdpSource()).isEqualTo(FreeRdpSource.AUTO);
        assertThat(loaded.preferredFreeRdpPath()).isEqualTo("/usr/bin/sdl-freerdp");
        assertThat(loaded.scalePercent()).isEqualTo(150);
        assertThat(loaded.fullscreen()).isTrue();
        assertThat(loaded.microphone()).isFalse();
        assertThat(loaded.multiMonitor()).isTrue();
        assertThat(loaded.autoRefreshMinutes()).isEqualTo(30);
        assertThat(loaded.extraArgs()).containsExactly("/bpp:24");
    }


    @Test
    @DisplayName("Legacy preferredBrowser config is ignored on load and save")
    void legacyBrowserSettingIsIgnored(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.json");
        Files.writeString(configFile, "{\"defaultTenant\":\"organizations\",\"preferredBrowser\":\"EDGE\"}");

        ConfigManager manager = new ConfigManager(configFile);
        ClientConfig loaded = manager.load();
        assertThat(loaded.defaultTenant()).isEqualTo("organizations");

        manager.save(loaded);
        assertThat(Files.readString(configFile)).doesNotContain("preferredBrowser");
    }
    @Test
    @DisplayName("WorkspaceCache persists and loads workspaces and cached icons")
    void testWorkspaceCache(@TempDir Path tempDir) {
        Path cacheFile = tempDir.resolve("workspaces.json");
        Path iconsDir = tempDir.resolve("icons");

        WorkspaceCache cache = new WorkspaceCache(cacheFile, iconsDir);

        WorkspaceResource r1 = new WorkspaceResource(
            "res1",
            "Cloud PC 1",
            ResourceType.DESKTOP,
            "Tenant 1",
            "t1",
            "Publisher 1",
            "/subscriptions/s1",
            URI.create("https://rdp.wvd.microsoft.com/r1.rdp"),
            URI.create("https://rdp.wvd.microsoft.com/i1.png")
        );

        Workspace ws = new Workspace("Workspace 1", "t1", "Tenant 1", List.of(r1));

        cache.saveWorkspaces(List.of(ws));

        List<Workspace> loaded = cache.loadWorkspaces();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().resources()).hasSize(1);
        assertThat(loaded.getFirst().resources().getFirst().title()).isEqualTo("Cloud PC 1");

        // Test icon caching
        byte[] fakePngBytes = new byte[]{ (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        cache.saveIcon(r1, fakePngBytes);

        assertThat(cache.hasCachedIcon(r1)).isTrue();
        Optional<byte[]> loadedBytes = cache.loadIconBytes(r1);
        assertThat(loadedBytes).isPresent().contains(fakePngBytes);
    }

    @Test
    @DisplayName("TokenStore saves and loads TokenResponse securely")
    void testTokenStore(@TempDir Path tempDir) throws IOException {
        Path tokenFile = tempDir.resolve("token-cache.json");
        TokenStore store = new TokenStore(tokenFile);

        assertThat(store.hasCachedToken()).isFalse();
        assertThat(store.load()).isEmpty();

        TokenResponse tokens = new TokenResponse(
            "access_token_abc",
            "refresh_token_def",
            "id_token_ghi",
            "Bearer",
            3600,
            "https://www.wvd.microsoft.com/.default",
            Instant.now().getEpochSecond()
        );

        store.save(tokens);

        assertThat(store.hasCachedToken()).isTrue();
        Optional<TokenResponse> loaded = store.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().accessToken()).isEqualTo("access_token_abc");
        assertThat(loaded.get().refreshToken()).isEqualTo("refresh_token_def");
        assertThat(loaded.get().idToken()).isEqualTo("id_token_ghi");

        store.clear();
        assertThat(store.hasCachedToken()).isFalse();
        assertThat(store.load()).isEmpty();
    }
}
