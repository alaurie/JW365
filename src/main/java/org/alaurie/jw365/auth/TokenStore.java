package org.alaurie.jw365.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.alaurie.jw365.config.XdgPaths;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe secure token storage supporting Linux Secret Service (GNOME Keyring)
 * with automatic fallback to hardware-bound AES-256-GCM encrypted local storage (POSIX 0600).
 */
public final class TokenStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path tokenFile;
    private final Path encryptedFile;

    public TokenStore() {
        this(XdgPaths.tokenCacheFile());
    }

    public TokenStore(Path tokenFile) {
        this.tokenFile = tokenFile;
        String fileName = tokenFile.getFileName().toString();
        if (fileName.endsWith(".json")) {
            this.encryptedFile = tokenFile.resolveSibling(fileName.substring(0, fileName.length() - 5) + ".enc");
        } else {
            this.encryptedFile = tokenFile.resolveSibling(fileName + ".enc");
        }
    }

    public Path getTokenFile() {
        return encryptedFile;
    }

    /**
     * Loads the cached token response.
     * Checks Secret Service Keyring -> Encrypted machine-bound file -> Legacy plaintext migration.
     */
    public synchronized Optional<TokenResponse> load() {
        // 1. Try GNOME Keyring via Secret Service (secret-tool) if available
        Optional<String> keyringJson = loadFromSecretTool();
        if (keyringJson.isPresent()) {
            try {
                TokenResponse tokens = MAPPER.readValue(keyringJson.get(), TokenResponse.class);
                return Optional.ofNullable(tokens);
            } catch (Exception ignored) {
            }
        }

        // 2. Try encrypted on-disk cache (AES-256-GCM)
        if (Files.exists(encryptedFile)) {
            try {
                byte[] rawEncrypted = Files.readAllBytes(encryptedFile);
                if (rawEncrypted.length > 0) {
                    byte[] decryptedBytes = MachineBoundCrypto.decrypt(rawEncrypted);
                    TokenResponse tokens = MAPPER.readValue(decryptedBytes, TokenResponse.class);
                    return Optional.ofNullable(tokens);
                }
            } catch (Exception e) {
                System.err.println("Warning: Could not decrypt token cache: " + e.getMessage());
            }
        }

        // 3. Fallback & migration from legacy plaintext file if present
        if (Files.exists(tokenFile)) {
            try {
                byte[] bytes = Files.readAllBytes(tokenFile);
                if (bytes.length > 0) {
                    TokenResponse tokens = MAPPER.readValue(bytes, TokenResponse.class);
                    // Automatically encrypt and delete the plaintext file
                    save(tokens);
                    Files.deleteIfExists(tokenFile);
                    return Optional.ofNullable(tokens);
                }
            } catch (Exception ignored) {
            }
        }

        return Optional.empty();
    }

    /**
     * Saves the token response securely:
     * - Stores in GNOME Keyring via secret-tool if available
     * - Encrypts on disk using machine-bound AES-256-GCM with POSIX 0600 permissions
     * - Shreds any legacy plaintext file
     */
    public synchronized void save(TokenResponse tokens) throws IOException {
        if (tokens == null) {
            clear();
            return;
        }

        byte[] jsonBytes = MAPPER.writeValueAsBytes(tokens);

        // 1. Store in Secret Service / GNOME Keyring if available
        saveToSecretTool(new String(jsonBytes, StandardCharsets.UTF_8));

        // 2. Encrypt using machine-bound AES-256-GCM and store atomically with 0600 permissions
        try {
            byte[] encryptedBytes = MachineBoundCrypto.encrypt(jsonBytes);

            Path parent = encryptedFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Path tempFile = encryptedFile.resolveSibling(encryptedFile.getFileName() + ".tmp." + System.nanoTime());
            try {
                Files.write(tempFile, encryptedBytes);

                // Set POSIX permissions to 0600 (owner read/write only)
                try {
                    Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                    Files.setPosixFilePermissions(tempFile, perms);
                } catch (UnsupportedOperationException ignored) {
                }

                Files.move(tempFile, encryptedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tempFile);
            }

            // Shred any legacy plaintext file
            Files.deleteIfExists(tokenFile);
        } catch (Exception e) {
            throw new IOException("Failed to encrypt and store token: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes both Keyring secret and encrypted on-disk token cache.
     */
    public synchronized void clear() {
        clearSecretTool();
        try {
            Files.deleteIfExists(encryptedFile);
            Files.deleteIfExists(tokenFile);
        } catch (IOException e) {
            System.err.println("Warning: Failed to delete token cache files: " + e.getMessage());
        }
    }

    /**
     * Checks if a cached token exists in either the Keyring or on-disk store.
     */
    public synchronized boolean hasCachedToken() {
        if (hasSecretTool() && loadFromSecretTool().isPresent()) {
            return true;
        }
        return (Files.exists(encryptedFile) && encryptedFile.toFile().length() > 0)
            || (Files.exists(tokenFile) && tokenFile.toFile().length() > 0);
    }

    // --------------------------------------------------------------------------
    // Linux Secret Service (secret-tool) Helper Methods
    // --------------------------------------------------------------------------

    private static boolean hasSecretTool() {
        return new File("/usr/bin/secret-tool").canExecute() || new File("/bin/secret-tool").canExecute();
    }

    private static Optional<String> loadFromSecretTool() {
        if (!hasSecretTool()) return Optional.empty();

        try {
            Process p = new ProcessBuilder("secret-tool", "lookup", "service", "jw365", "account", "default").start();
            boolean finished = p.waitFor(2, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!out.isBlank()) {
                    return Optional.of(out);
                }
            } else {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private static void saveToSecretTool(String secret) {
        if (!hasSecretTool()) return;

        try {
            Process p = new ProcessBuilder("secret-tool", "store", "--label=JW365 Token", "service", "jw365", "account", "default").start();
            p.getOutputStream().write(secret.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().flush();
            p.getOutputStream().close();
            p.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static void clearSecretTool() {
        if (!hasSecretTool()) return;

        try {
            Process p = new ProcessBuilder("secret-tool", "clear", "service", "jw365", "account", "default").start();
            p.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }
}
