package org.alaurie.jw365.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MachineBoundCryptoTest {

    @Test
    @DisplayName("MachineBoundCrypto encrypts and decrypts accurately with AES-256-GCM")
    void testEncryptDecrypt() throws Exception {
        String plaintext = "{\"access_token\": \"secret_token_12345\", \"user\": \"alex\"}";
        byte[] originalBytes = plaintext.getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = MachineBoundCrypto.encrypt(originalBytes);

        // Ciphertext should not be plaintext
        assertThat(encrypted).isNotEqualTo(originalBytes);
        assertThat(encrypted.length).isGreaterThan(originalBytes.length);

        byte[] decrypted = MachineBoundCrypto.decrypt(encrypted);
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("MachineBoundCrypto produces different ciphertexts for the same plaintext due to random IV and salt")
    void testNoncesAndSaltsDiffer() throws Exception {
        byte[] data = "repeatable test string".getBytes(StandardCharsets.UTF_8);

        byte[] enc1 = MachineBoundCrypto.encrypt(data);
        byte[] enc2 = MachineBoundCrypto.encrypt(data);

        assertThat(enc1).isNotEqualTo(enc2);

        // Both decrypt to the same content
        assertThat(MachineBoundCrypto.decrypt(enc1)).isEqualTo(data);
        assertThat(MachineBoundCrypto.decrypt(enc2)).isEqualTo(data);
    }

    @Test
    @DisplayName("MachineBoundCrypto detects tampering and fails authentication")
    void testTamperDetection() throws Exception {
        byte[] data = "important secret".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = MachineBoundCrypto.encrypt(data);

        // Flip a bit in the ciphertext payload
        byte[] tampered = Arrays.copyOf(encrypted, encrypted.length);
        tampered[tampered.length - 1] ^= 0x01;

        assertThatThrownBy(() -> MachineBoundCrypto.decrypt(tampered))
            .isInstanceOf(Exception.class);
    }
}
