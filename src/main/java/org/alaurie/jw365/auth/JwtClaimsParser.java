package org.alaurie.jw365.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Utility for extracting user claims from Entra ID JWT ID tokens without external crypto dependencies.
 */
public final class JwtClaimsParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private JwtClaimsParser() {
    }

    /**
     * Parses the payload section of a JWT string into a {@link UserClaims} instance.
     *
     * @param jwtRaw the raw JWT token string
     * @return extracted UserClaims
     * @throws IllegalArgumentException if the JWT format is invalid
     */
    public static UserClaims parseIdToken(String jwtRaw) {
        if (jwtRaw == null || jwtRaw.isBlank()) {
            return new UserClaims(null, null, null, null, null, null, Collections.emptyList());
        }

        String[] parts = jwtRaw.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid JWT token format: expected at least 2 parts separated by '.'");
        }

        String payloadB64 = parts[1];
        // Handle unpadded base64url
        int missingPadding = (4 - (payloadB64.length() % 4)) % 4;
        String padded = payloadB64 + "=".repeat(missingPadding);

        byte[] jsonBytes;
        try {
            jsonBytes = URL_DECODER.decode(padded);
        } catch (IllegalArgumentException e) {
            // Fallback: try direct base64 decode
            jsonBytes = Base64.getDecoder().decode(padded);
        }

        String json = new String(jsonBytes, StandardCharsets.UTF_8);
        return parseClaimsJson(json);
    }

    /**
     * Parses the JSON payload directly into {@link UserClaims}.
     */
    public static UserClaims parseClaimsJson(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);

            String upn = textOrNull(root, "upn");
            if (upn == null) {
                upn = textOrNull(root, "unique_name");
            }

            String preferredUsername = textOrNull(root, "preferred_username");
            String name = textOrNull(root, "name");
            String email = textOrNull(root, "email");
            String tid = textOrNull(root, "tid");
            String oid = textOrNull(root, "oid");

            List<String> roles = new ArrayList<>();
            JsonNode rolesNode = root.get("roles");
            if (rolesNode != null && rolesNode.isArray()) {
                for (JsonNode r : rolesNode) {
                    roles.add(r.asString());
                }
            }

            return new UserClaims(upn, name, email, preferredUsername, tid, oid, roles);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JWT claims JSON: " + e.getMessage(), e);
        }
    }

    private static String textOrNull(JsonNode node, String fieldName) {
        JsonNode f = node.get(fieldName);
        return (f != null && !f.isNull()) ? f.asString() : null;
    }
}
