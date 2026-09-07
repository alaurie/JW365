package org.alaurie.jw365.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages reading and writing application configuration to disk.
 */
public final class ConfigManager {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path configFile;
    private final AtomicReference<ClientConfig> cachedConfig = new AtomicReference<>();

    public ConfigManager() {
        this(XdgPaths.configFile());
    }

    public ConfigManager(Path configFile) {
        this.configFile = Objects.requireNonNull(configFile, "configFile must not be null");
    }

    public Path getConfigFile() {
        return configFile;
    }

    /**
     * Loads the current configuration from disk, creating default if not found.
     */
    public ClientConfig load() {
        if (!Files.exists(configFile)) {
            ClientConfig def = ClientConfig.defaultConfig();
            cachedConfig.set(def);
            return def;
        }

        try {
            ClientConfig config = MAPPER.readValue(configFile.toFile(), ClientConfig.class);
            cachedConfig.set(config);
            return config;
        } catch (Exception e) {
            System.err.println("Warning: Failed to parse config file " + configFile + ": " + e.getMessage() + ". Using defaults.");
            ClientConfig def = ClientConfig.defaultConfig();
            cachedConfig.set(def);
            return def;
        }
    }

    /**
     * Returns the cached configuration in memory or loads it if not yet loaded.
     */
    public ClientConfig get() {
        ClientConfig c = cachedConfig.get();
        if (c == null) {
            return load();
        }
        return c;
    }

    /**
     * Saves the updated configuration to disk atomically.
     */
    public synchronized void save(ClientConfig config) throws IOException {
        Objects.requireNonNull(config, "config must not be null");

        Path parent = configFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Path tempFile = configFile.resolveSibling(configFile.getFileName() + ".tmp." + System.nanoTime());
        try {
            MAPPER.writeValue(tempFile.toFile(), config);
            Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            cachedConfig.set(config);
        } finally {
            if (Files.exists(tempFile)) {
                try {
                    Files.delete(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
