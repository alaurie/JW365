package org.alaurie.jw365.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
            ? Path.of(xdgConfig)
            : Path.of(System.getProperty("user.home"), ".config");
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
            ? Path.of(xdgData)
            : Path.of(System.getProperty("user.home"), ".local", "share");
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
            ? Path.of(xdgCache)
            : Path.of(System.getProperty("user.home"), ".cache");
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
     * Path to persistent WebView user data (localStorage, sessionStorage, IndexedDB, WebKit cache).
     */
    public static Path webViewDataDir() {
        Path dir = dataDir().resolve("webview");
        ensureDir(dir);
        try {
            Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (Exception _) { }
        return dir;
    }

    /**
     * Retains the most recent log files and prunes older session logs to prevent disk clutter.
     */
    public static synchronized void pruneOldLogs(int maxFilesToKeep) {
        pruneOldLogs(logsDir(), maxFilesToKeep);
    }

    /** Prunes only the supplied directory; useful for isolated callers and tests. */
    public static synchronized void pruneOldLogs(Path dir, int maxFilesToKeep) {
        if (dir == null || maxFilesToKeep < 0) return;
        ensureDir(dir);
        try {
            try { Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
            catch (UnsupportedOperationException _) { }
            try (var stream = Files.list(dir)) {
                java.util.List<Path> logFiles = stream.filter(p -> p.getFileName().toString().startsWith("session_")
                        && p.getFileName().toString().endsWith(".log"))
                    .sorted((a, b) -> {
                        try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                        catch (IOException e) { return 0; }
                    }).toList();
                long retainedBytes = 0;
                for (int i = 0; i < logFiles.size(); i++) {
                    Path log = logFiles.get(i);
                    long size = Files.size(log);
                    if (i >= maxFilesToKeep || retainedBytes + size > 32L * 1024 * 1024) Files.deleteIfExists(log);
                    else {
                        retainedBytes += size;
                        try { Files.setPosixFilePermissions(log, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                        catch (UnsupportedOperationException _) { }
                    }
                }
            }
        } catch (IOException _) { }
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
