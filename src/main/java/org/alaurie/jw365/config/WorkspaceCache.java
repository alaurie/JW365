package org.alaurie.jw365.config;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import org.alaurie.jw365.feed.Workspace;
import org.alaurie.jw365.feed.WorkspaceResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Caches discovered workspace lists and resource icons on local storage for instant application startup.
 */
public final class WorkspaceCache {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();

    private final Path cacheFile;
    private final Path iconsDirectory;

    public WorkspaceCache() {
        this(XdgPaths.workspacesCacheFile(), XdgPaths.iconsDir());
    }

    public WorkspaceCache(Path cacheFile, Path iconsDirectory) {
        this.cacheFile = cacheFile;
        this.iconsDirectory = iconsDirectory;
    }

    /**
     * Loads cached workspaces from disk.
     */
    public synchronized List<Workspace> loadWorkspaces() {
        if (!Files.exists(cacheFile)) {
            return Collections.emptyList();
        }

        try {
            List<Workspace> workspaces = MAPPER.readValue(cacheFile.toFile(), new TypeReference<>() {});
            return workspaces != null ? workspaces : Collections.emptyList();
        } catch (Exception e) {
            System.err.println("Warning: Failed to load cached workspaces: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Saves workspaces to disk atomically.
     */
    public synchronized void saveWorkspaces(List<Workspace> workspaces) {
        if (workspaces == null) {
            return;
        }

        Path parent = cacheFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ignored) {
            }
        }

        Path tempFile = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp." + System.nanoTime());
        try {
            MAPPER.writeValue(tempFile.toFile(), workspaces);
            try {
                Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to save workspace cache: " + e.getMessage());
        } finally {
            if (Files.exists(tempFile)) {
                try {
                    Files.delete(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Clears cached workspaces from disk.
     */
    public synchronized void clear() {
        if (Files.exists(cacheFile)) {
            try {
                Files.delete(cacheFile);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Retrieves the path for a resource icon in the local cache.
     */
    public Path getIconPath(WorkspaceResource resource) {
        return iconsDirectory.resolve(resource.cacheFileName() + ".png");
    }

    /**
     * Checks if a cached icon exists for a resource.
     */
    public boolean hasCachedIcon(WorkspaceResource resource) {
        Path p = getIconPath(resource);
        try {
            return Files.exists(p) && Files.size(p) > 0 && Files.size(p) <= 2 * 1024 * 1024;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Saves icon bytes into the local cache.
     */
    public void saveIcon(WorkspaceResource resource, byte[] iconBytes) {
        if (iconBytes == null || iconBytes.length == 0 || iconBytes.length > 2 * 1024 * 1024) {
            return;
        }

        Path target = getIconPath(resource);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp." + System.nanoTime());
        try {
            if (!Files.exists(iconsDirectory)) {
                Files.createDirectories(iconsDirectory);
            }
            Files.write(temp, iconBytes);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to write icon for " + resource.title() + ": " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Loads cached icon bytes if present.
     */
    public Optional<byte[]> loadIconBytes(WorkspaceResource resource) {
        Path target = getIconPath(resource);
        if (Files.exists(target)) {
            try {
                long size = Files.size(target);
                if (size > 0 && size <= 2 * 1024 * 1024) {
                    return Optional.of(Files.readAllBytes(target));
                }
            } catch (IOException ignored) {
            }
        }
        return Optional.empty();
    }
}
