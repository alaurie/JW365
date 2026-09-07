package org.alaurie.jw365.gui.state;

import org.alaurie.jw365.config.ClientConfig;
import org.alaurie.jw365.config.ConfigManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppStateTest {

    @Test
    @DisplayName("AppState initializes default reactive properties")
    void testAppStateProperties(@TempDir Path tempDir) {
        ConfigManager cfg = new ConfigManager(tempDir.resolve("config.json"));
        AppState state = new AppState(null, null, cfg, null, null, null);

        assertThat(state.authenticatedProperty().get()).isFalse();
        assertThat(state.currentUserProperty().get()).isNull();
        assertThat(state.getWorkspaces()).isEmpty();
        assertThat(state.loadingProperty().get()).isFalse();
        assertThat(state.statusMessageProperty().get()).isEqualTo("Ready");
        assertThat(state.searchFilterProperty().get()).isEmpty();
        assertThat(state.lastSyncedProperty().get()).isNull();
    }

    @Test
    @DisplayName("AppState configuration updates persist and propagate")
    void testConfigUpdates(@TempDir Path tempDir) {
        ConfigManager cfg = new ConfigManager(tempDir.resolve("config.json"));
        AppState state = new AppState(null, null, cfg, null, null, null);

        ClientConfig customConfig = new ClientConfig(
            "test-tenant-id",
            null,
            "EDGE",
            125,
            true,
            true,
            true,
            false,
            true,
            20,
            List.of()
        );

        state.updateConfig(customConfig);
        ClientConfig active = state.getConfigManager().get();

        assertThat(active.defaultTenant()).isEqualTo("test-tenant-id");
        assertThat(active.scalePercent()).isEqualTo(125);
        assertThat(active.fullscreen()).isTrue();
        assertThat(active.autoRefreshMinutes()).isEqualTo(20);
    }
}
