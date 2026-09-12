package org.alaurie.jw365.auth;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;

/**
 * Machine-bound AES-256-GCM authenticated encryption for persistent token caching.
 * using 100,000 rounds of PBKDF2-HMAC-SHA256 with unique random salts and IVs.
 */
public final class MachineBoundCrypto {

    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private MachineBoundCrypto() {
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM with a random salt and IV.
     * Output format: [16-byte salt] + [12-byte IV] + [ciphertext + 16-byte GCM tag].
     */
    public static byte[] encrypt(byte[] plaintext) throws Exception {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);

        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);

        SecretKey key = deriveKey(salt);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        byte[] ciphertext = cipher.doFinal(plaintext);

        ByteBuffer buf = ByteBuffer.allocate(SALT_LENGTH + IV_LENGTH + ciphertext.length);
        buf.put(salt);
        buf.put(iv);
        buf.put(ciphertext);
        return buf.array();
    }

    /**
     * Decrypts payload bytes using AES-256-GCM with the embedded salt and IV.
     * Throws an exception if the payload is tampered with or key does not match.
     */
    public static byte[] decrypt(byte[] payload) throws Exception {
        if (payload == null || payload.length < SALT_LENGTH + IV_LENGTH + (TAG_LENGTH_BITS / 8)) {
            throw new IllegalArgumentException("Payload too short to be valid ciphertext");
        }

        ByteBuffer buf = ByteBuffer.wrap(payload);
        byte[] salt = new byte[SALT_LENGTH];
        buf.get(salt);

        byte[] iv = new byte[IV_LENGTH];
        buf.get(iv);

        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        SecretKey key = deriveKey(salt);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    private static SecretKey deriveKey(byte[] salt) throws Exception {
        String machineId = readMachineId();
        String user = System.getProperty("user.name", "default");
        char[] secretChars = (machineId + ":" + user).toCharArray();

        PBEKeySpec spec = new PBEKeySpec(secretChars, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }

    private static String readMachineId() {
        for (String candidate : new String[]{"/etc/machine-id", "/var/lib/dbus/machine-id"}) {
            try {
                Path p = Path.of(candidate);
                if (Files.isReadable(p)) {
                    String id = Files.readString(p, StandardCharsets.UTF_8).trim();
                    if (!id.isBlank()) {
                        return id;
                    }
                }
            } catch (Exception _) {
            }
        }
        return "jw365-fallback-" + System.getProperty("user.home");
    }
}
