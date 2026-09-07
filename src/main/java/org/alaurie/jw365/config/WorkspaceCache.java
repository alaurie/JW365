package org.alaurie.jw365.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

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
        if (workspaces == null || workspaces.isEmpty()) {
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
            Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
        return iconsDirectory.resolve(resource.sanitizedFileName() + ".png");
    }

    /**
     * Checks if a cached icon exists for a resource.
     */
    public boolean hasCachedIcon(WorkspaceResource resource) {
        Path p = getIconPath(resource);
        return Files.exists(p) && p.toFile().length() > 0;
    }

    /**
     * Saves icon bytes into the local cache.
     */
    public void saveIcon(WorkspaceResource resource, byte[] iconBytes) {
        if (iconBytes == null || iconBytes.length == 0) {
            return;
        }

        Path target = getIconPath(resource);
        try {
            if (!Files.exists(iconsDirectory)) {
                Files.createDirectories(iconsDirectory);
            }
            Files.write(target, iconBytes);
        } catch (IOException e) {
            System.err.println("Warning: Failed to write icon for " + resource.title() + ": " + e.getMessage());
        }
    }

    /**
     * Loads cached icon bytes if present.
     */
    public Optional<byte[]> loadIconBytes(WorkspaceResource resource) {
        Path target = getIconPath(resource);
        if (Files.exists(target)) {
            try {
                return Optional.of(Files.readAllBytes(target));
            } catch (IOException ignored) {
            }
        }
        return Optional.empty();
    }
}
