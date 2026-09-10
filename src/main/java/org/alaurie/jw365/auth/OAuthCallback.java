package org.alaurie.jw365.auth;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed authorization response returned to the native-client redirect.
 *
 * <p>The embedded sign-in flow deliberately requests {@code response_mode=query};
 * form-post responses are not exposed as a navigated URL by JavaFX WebView.</p>
 */
public record OAuthCallback(
    String code,
    String state,
    String error,
    String errorDescription
) {
    public static boolean isRedirect(String callbackUrl, URI expected) {
        try {
            URI candidate = URI.create(callbackUrl);
            return expected.getScheme().equalsIgnoreCase(candidate.getScheme())
                && expected.getHost().equalsIgnoreCase(candidate.getHost())
                && expected.getPort() == candidate.getPort()
                && expected.getPath().equals(candidate.getPath());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Parses an OAuth response URL, returning empty for malformed or parameterless URLs. */
    public static Optional<OAuthCallback> parse(String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(callbackUrl);
            if (uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
                return Optional.empty();
            }
            return parseQuery(uri.getRawQuery());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Parses an already extracted URL query. Package-private for focused callback tests. */
    static Optional<OAuthCallback> parseQuery(String rawQuery) {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String pair : rawQuery.split("&", -1)) {
                if (pair.isEmpty()) {
                    continue;
                }
                int separator = pair.indexOf('=');
                String rawName = separator < 0 ? pair : pair.substring(0, separator);
                String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
                String name = decode(rawName);
                if (name.isBlank() || values.putIfAbsent(name, decode(rawValue)) != null) {
                    return Optional.empty();
                }
            }
            if (values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new OAuthCallback(
                values.get("code"),
                values.get("state"),
                values.get("error"),
                values.get("error_description")
            ));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean hasError() {
        return error != null && !error.isBlank();
    }

    public boolean isSuccess() {
        return !hasError() && code != null && !code.isBlank();
    }

    public boolean matchesState(String expectedState) {
        return expectedState != null && expectedState.equals(state);
    }

    public String errorMessage() {
        if (errorDescription != null && !errorDescription.isBlank()) {
            return errorDescription;
        }
        return error == null || error.isBlank() ? "Microsoft sign-in failed" : error;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
