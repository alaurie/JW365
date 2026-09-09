package org.alaurie.jw365.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves standard XDG Base Directory specification paths on Linux.
 */
public final class XdgPaths {

    private static final String APP_NAME = "jw365";

    private XdgPaths() {
    }

    /**
     * Resolves {@code $XDG_CONFIG_HOME/jw365} (default: {@code ~/.config/jw365}).
     */
    public static Path configDir() {
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdgConfig != null && !xdgConfig.isBlank())
            ? Paths.get(xdgConfig)
            : Paths.get(System.getProperty("user.home"), ".config");
        Path dir = base.resolve(APP_NAME);
        ensureDir(dir);
        return dir;
    }

    /**
     * Resolves {@code $XDG_DATA_HOME/jw365} (default: {@code ~/.local/share/jw365}).
     */
    public static Path dataDir() {
        String xdgData = System.getenv("XDG_DATA_HOME");
        Path base = (xdgData != null && !xdgData.isBlank())
            ? Paths.get(xdgData)
            : Paths.get(System.getProperty("user.home"), ".local", "share");
        Path dir = base.resolve(APP_NAME);
        ensureDir(dir);
        return dir;
    }

    /**
     * Resolves {@code $XDG_CACHE_HOME/jw365} (default: {@code ~/.cache/jw365}).
     */
    public static Path cacheDir() {
        String xdgCache = System.getenv("XDG_CACHE_HOME");
        Path base = (xdgCache != null && !xdgCache.isBlank())
            ? Paths.get(xdgCache)
            : Paths.get(System.getProperty("user.home"), ".cache");
        Path dir = base.resolve(APP_NAME);
        ensureDir(dir);
        return dir;
    }

    /**
     * Path to cached icon storage.
     */
    public static Path iconsDir() {
        Path dir = cacheDir().resolve("icons");
        ensureDir(dir);
        return dir;
    }

    /**
     * Path to downloaded .rdp files storage.
     */
    public static Path rdpFeedDir() {
        Path dir = dataDir().resolve("feed");
        ensureDir(dir);
        return dir;
    }

    /**
     * Path to session logs storage.
     */
    public static Path logsDir() {
        Path dir = dataDir().resolve("logs");
        ensureDir(dir);
        return dir;
    }

    /**
     * Retains the most recent log files and prunes older session logs to prevent disk clutter.
     */
    public static void pruneOldLogs(int maxFilesToKeep) {
        Path dir = logsDir();
        try (var stream = Files.list(dir)) {
            java.util.List<Path> logFiles = stream
                .filter(p -> p.getFileName().toString().startsWith("session_") && p.getFileName().toString().endsWith(".log"))
                .sorted((a, b) -> {
                    try {
                        return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .toList();

            if (logFiles.size() > maxFilesToKeep) {
                for (int i = maxFilesToKeep; i < logFiles.size(); i++) {
                    try {
                        Files.deleteIfExists(logFiles.get(i));
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Path to token cache JSON file.
     */
    public static Path tokenCacheFile() {
        return dataDir().resolve("token-cache.json");
    }

    /**
     * Path to client settings JSON file.
     */
    public static Path configFile() {
        return configDir().resolve("config.json");
    }

    /**
     * Path to cached workspaces JSON file.
     */
    public static Path workspacesCacheFile() {
        return dataDir().resolve("workspaces.json");
    }

    public static boolean isFlatpak() {
        return System.getenv("FLATPAK_ID") != null || Files.exists(Path.of("/.flatpak-info"));
    }

    private static void ensureDir(Path dir) {
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to create directory: " + dir + " (" + e.getMessage() + ")");
        }
    }
}
