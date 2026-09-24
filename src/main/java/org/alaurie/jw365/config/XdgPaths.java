package org.alaurie.jw365.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/**
 * Resolves standard XDG Base Directory specification paths on Linux.
 */
public final class XdgPaths {
    private static final String APP_NAME = "jw365";
    private static final boolean IS_FLATPAK = checkFlatpak();

    private XdgPaths() {}

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
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (Exception _) {
        }
        return dir;
    }

    /**
     * Path to session logs storage.
     */
    public static Path logsDir() {
        Path dir = dataDir().resolve("logs");
        ensureDir(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (Exception _) {
        }
        return dir;
    }

    /**
     * Path to persistent WebView user data (localStorage, sessionStorage,
     * IndexedDB, WebKit cache).
     */
    public static Path webViewDataDir() {
        Path dir = dataDir().resolve("webview");
        ensureDir(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (Exception _) {
        }
        return dir;
    }

    /**
     * Retains the most recent log files and prunes older session logs to
     * prevent disk clutter.
     */
    public static synchronized void pruneOldLogs(int maxFilesToKeep) {
        pruneOldLogs(logsDir(), maxFilesToKeep);
    }

    /** Prunes only the supplied directory; useful for isolated callers and tests. */
    public static synchronized void pruneOldLogs(Path dir, int maxFilesToKeep) {
        if (dir == null || maxFilesToKeep < 0) {
            return;
        }
        ensureDir(dir);
        try {
            try {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException _) {
            }
            try (var stream = Files.list(dir)) {
                List<LogEntry> logFiles = stream.filter(p -> p.getFileName()
                                .toString()
                                .startsWith("session_")
                                && p.getFileName()
                                    .toString()
                                    .endsWith(".log"))
                        .map(p -> {
                            try {
                                return new LogEntry(p, Files.getLastModifiedTime(p), Files.size(p));
                            } catch (IOException _) {
                                return new LogEntry(p, FileTime.fromMillis(0), 0L);
                            }
                        })
                        .sorted((a, b) -> b.lastModified().compareTo(a.lastModified()))
                        .toList();
                long retainedBytes = 0;
                for (int i = 0; i < logFiles.size(); i++) {
                    LogEntry entry = logFiles.get(i);
                    if (i >= maxFilesToKeep || retainedBytes + entry.size() > 32L * 1024 * 1024) {
                        Files.deleteIfExists(entry.path());
                    } else {
                        retainedBytes += entry.size();
                        try {
                            Files.setPosixFilePermissions(entry.path(), PosixFilePermissions.fromString("rw-------"));
                        } catch (UnsupportedOperationException _) {
                        }
                    }
                }
            }
        } catch (IOException _) {
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
        return IS_FLATPAK;
    }

    private static boolean checkFlatpak() {
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

    private record LogEntry(Path path, FileTime lastModified, long size) {}
}
