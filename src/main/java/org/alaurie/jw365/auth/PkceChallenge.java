package org.alaurie.jw365.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Immutable record representing a PKCE (Proof Key for Code Exchange) challenge.
 *
 * @param codeVerifier  The cryptographically random high-entropy secret verifier.
 * @param codeChallenge The SHA-256 base64url-encoded challenge sent in the authorize request.
 * @param state         The random state parameter used for CSRF protection.
 */
public record PkceChallenge(String codeVerifier, String codeChallenge, String state) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    public PkceChallenge {
        Objects.requireNonNull(codeVerifier, "codeVerifier must not be null");
        Objects.requireNonNull(codeChallenge, "codeChallenge must not be null");
        Objects.requireNonNull(state, "state must not be null");
    }

    /**
     * Generates a new cryptographically secure PKCE challenge with 64 random bytes (86 chars base64url).
     *
     * @return a fresh PkceChallenge instance
     */
    public static PkceChallenge create() {
        byte[] verifierBytes = new byte[64];
        RANDOM.nextBytes(verifierBytes);
        String verifier = URL_ENCODER.encodeToString(verifierBytes);

        byte[] stateBytes = new byte[32];
        RANDOM.nextBytes(stateBytes);
        String state = URL_ENCODER.encodeToString(stateBytes);

        String challenge = computeS256(verifier);
        return new PkceChallenge(verifier, challenge, state);
    }

    /**
     * Computes the S256 code challenge for a given verifier string.
     */
    public static String computeS256(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm must be available in JVM", e);
        }
    }
}
