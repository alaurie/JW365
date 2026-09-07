package org.alaurie.jw365.auth;

import java.net.URI;

/**
 * Sealed result type representing the outcome of an authentication attempt.
 */
public sealed interface AuthResult {

    /**
     * Authentication succeeded with valid tokens and extracted user claims.
     */
    record Success(TokenResponse tokens, UserClaims claims) implements AuthResult {
    }

    /**
     * Device code authorization is waiting for the user to complete sign-in.
     */
    record DeviceCodeRequired(
        String userCode,
        URI verificationUri,
        String message,
        DeviceCodeResponse rawResponse
    ) implements AuthResult {
    }

    /**
     * Authentication failed with an error message.
     */
    record Failure(String errorCode, String errorMessage, Throwable cause) implements AuthResult {
        public Failure(String errorCode, String errorMessage) {
            this(errorCode, errorMessage, null);
        }
    }
}
