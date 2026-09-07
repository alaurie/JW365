package org.alaurie.jw365.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.alaurie.jw365.config.XdgPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

/**
 * Thread-safe persistent token storage supporting XDG Base Directory Specification
 * and secure POSIX file permissions (0600).
 */
public final class TokenStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path tokenFile;

    public TokenStore() {
        this(XdgPaths.tokenCacheFile());
    }

    public TokenStore(Path tokenFile) {
        this.tokenFile = tokenFile;
    }

    public Path getTokenFile() {
        return tokenFile;
    }

    /**
     * Loads the cached token response if present and readable.
     */
    public synchronized Optional<TokenResponse> load() {
        if (!Files.exists(tokenFile)) {
            return Optional.empty();
        }

        try {
            byte[] bytes = Files.readAllBytes(tokenFile);
            if (bytes.length == 0) {
                return Optional.empty();
            }
            TokenResponse tokens = MAPPER.readValue(bytes, TokenResponse.class);
            return Optional.ofNullable(tokens);
        } catch (Exception e) {
            System.err.println("Warning: Failed to load token cache from " + tokenFile + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Saves the token response atomically and restricts POSIX permissions to 0600.
     */
    public synchronized void save(TokenResponse tokens) throws IOException {
        if (tokens == null) {
            clear();
            return;
        }

        Path parent = tokenFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        Path tempFile = tokenFile.resolveSibling(tokenFile.getFileName() + ".tmp." + System.nanoTime());
        try {
            MAPPER.writeValue(tempFile.toFile(), tokens);

            // Set POSIX permissions to 0600 (owner read/write only) if supported
            try {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(tempFile, perms);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g. Windows during development)
            }

            Files.move(tempFile, tokenFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
     * Deletes the cached token file.
     */
    public synchronized void clear() {
        if (Files.exists(tokenFile)) {
            try {
                Files.delete(tokenFile);
            } catch (IOException e) {
                System.err.println("Warning: Failed to delete token cache file: " + e.getMessage());
            }
        }
    }

    /**
     * Checks if a non-empty token cache exists.
     */
    public synchronized boolean hasCachedToken() {
        return Files.exists(tokenFile) && tokenFile.toFile().length() > 0;
    }
}
