package org.alaurie.jw365.auth;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import org.alaurie.jw365.config.XdgPaths;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe secure token storage supporting Linux Secret Service (GNOME Keyring)
 * with automatic fallback to hardware-bound AES-256-GCM encrypted local storage (POSIX 0600).
 */
public final class TokenStore {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();
    private static final Duration SECRET_TOOL_LOOKUP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SECRET_TOOL_WRITE_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration SECRET_TOOL_CLEAR_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_SECRET_TOOL_OUTPUT_BYTES = 1024 * 1024;

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

    /**
     * Loads the cached token response.
     * Checks Secret Service Keyring -> Encrypted machine-bound file -> Legacy plaintext migration.
     */
    public synchronized Optional<TokenResponse> load() {
        Optional<String> keyringJson = loadFromSecretTool();
        if (keyringJson.isPresent()) {
            try {
                TokenResponse tokens = MAPPER.readValue(keyringJson.get(), TokenResponse.class);
                return Optional.ofNullable(tokens);
            } catch (Exception e) {
                System.err.println("Warning: Ignoring invalid token in Secret Service: " + e.getMessage());
            }
        }

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

        if (Files.exists(tokenFile)) {
            try {
                byte[] bytes = Files.readAllBytes(tokenFile);
                if (bytes.length > 0) {
                    TokenResponse tokens = MAPPER.readValue(bytes, TokenResponse.class);
                    save(tokens);
                    Files.deleteIfExists(tokenFile);
                    return Optional.ofNullable(tokens);
                }
            } catch (Exception e) {
                // Keep the plaintext file so a transient Secret Service/filesystem failure can be retried.
                System.err.println("Warning: Failed to migrate plaintext token cache; it was retained for retry: "
                    + e.getMessage());
            }
        }

        return Optional.empty();
    }

    /**
     * Saves the token response securely.
     */
    public synchronized void save(TokenResponse tokens) throws IOException {
        if (tokens == null) {
            clear();
            return;
        }

        byte[] jsonBytes = MAPPER.writeValueAsBytes(tokens);
        if (!saveToSecretTool(new String(jsonBytes, StandardCharsets.UTF_8))) {
            System.err.println("Warning: Secret Service token storage failed; using encrypted file fallback");
        }

        try {
            byte[] encryptedBytes = MachineBoundCrypto.encrypt(jsonBytes);
            Path parent = encryptedFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Path tempFile = encryptedFile.resolveSibling(encryptedFile.getFileName() + ".tmp." + System.nanoTime());
            try {
                Files.write(tempFile, encryptedBytes);
                try {
                    Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
                    Files.setPosixFilePermissions(tempFile, perms);
                } catch (UnsupportedOperationException ignored) {
                }
                try {
                    Files.move(tempFile, encryptedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tempFile, encryptedFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
            Files.deleteIfExists(tokenFile);
        } catch (Exception e) {
            throw new IOException("Failed to encrypt and store token: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes both Keyring secret and encrypted on-disk token cache.
     */
    public synchronized boolean clear() {
        boolean keyringCleared = clearSecretTool();
        boolean filesCleared = true;
        try {
            Files.deleteIfExists(encryptedFile);
            Files.deleteIfExists(tokenFile);
        } catch (IOException e) {
            filesCleared = false;
            System.err.println("Warning: Failed to delete token cache files: " + e.getMessage());
        }
        return keyringCleared && filesCleared;
    }

    private static boolean clearSecretTool() {
        if (!hasSecretTool()) {
            return true;
        }
        SecretToolResult result = runSecretTool(
            List.of("secret-tool", "clear", "service", "jw365", "account", "default"),
            null,
            SECRET_TOOL_CLEAR_TIMEOUT
        );
        if (result.timedOut()) {
            System.err.println("Warning: Secret Service clear timed out");
        } else if (!result.success()) {
            System.err.println("Warning: Secret Service clear failed");
        }
        return result.success();
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

    private static boolean hasSecretTool() {
        return new File("/usr/bin/secret-tool").canExecute() || new File("/bin/secret-tool").canExecute();
    }

    private static Optional<String> loadFromSecretTool() {
        if (!hasSecretTool()) {
            return Optional.empty();
        }
        SecretToolResult result = runSecretTool(
            List.of("secret-tool", "lookup", "service", "jw365", "account", "default"),
            null,
            SECRET_TOOL_LOOKUP_TIMEOUT
        );
        if (result.success()) {
            String out = new String(result.output(), StandardCharsets.UTF_8).trim();
            if (!out.isBlank()) {
                return Optional.of(out);
            }
        } else if (result.timedOut()) {
            System.err.println("Warning: Secret Service lookup timed out");
        }
        return Optional.empty();
    }

    private static boolean saveToSecretTool(String secret) {
        if (!hasSecretTool()) {
            return true;
        }
        SecretToolResult result = runSecretTool(
            List.of("secret-tool", "store", "--label=JW365 Token", "service", "jw365", "account", "default"),
            secret.getBytes(StandardCharsets.UTF_8),
            SECRET_TOOL_WRITE_TIMEOUT
        );
        if (result.timedOut()) {
            System.err.println("Warning: Secret Service store timed out");
        } else if (!result.success()) {
            System.err.println("Warning: Secret Service store failed");
        }
        return result.success();

    }


    private static SecretToolResult runSecretTool(List<String> command, byte[] stdin, Duration timeout) {
        Process process = null;
        AtomicReference<byte[]> output = new AtomicReference<>(new byte[0]);
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process activeProcess = process;
            Thread reader = Thread.ofVirtual().start(() -> output.set(readProcessOutput(activeProcess.getInputStream())));
            try {
                if (stdin != null) {
                    process.getOutputStream().write(stdin);
                }
            } finally {
                process.getOutputStream().close();
            }

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                }
            }
            reader.join(1000);
            boolean success = finished && !process.isAlive() && process.exitValue() == 0;
            return new SecretToolResult(success, !finished, output.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new SecretToolResult(false, true, output.get());
        } catch (IOException e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new SecretToolResult(false, false, output.get());
        }
    }

    private static byte[] readProcessOutput(InputStream input) {
        try (input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                int retained = Math.min(read, MAX_SECRET_TOOL_OUTPUT_BYTES - total);
                if (retained > 0) {
                    output.write(buffer, 0, retained);
                    total += retained;
                }
            }
            return output.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private record SecretToolResult(boolean success, boolean timedOut, byte[] output) {
    }
}
