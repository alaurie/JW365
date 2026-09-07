package org.alaurie.jw365.auth;

import java.nio.file.Path;

/**
 * Information about a detected web browser on the host system.
 *
 * @param type         the browser classification
 * @param displayName  human-readable name (e.g. "Microsoft Edge (Work Profile SSO)")
 * @param executablePath path to the binary or null if system default / flatpak
 * @param isEdge       true if this is Microsoft Edge
 * @param isFlatpak    true if launched via flatpak
 * @param flatpakAppId flatpak ID if applicable
 */
public record BrowserInfo(
    BrowserType type,
    String displayName,
    Path executablePath,
    boolean isEdge,
    boolean isFlatpak,
    String flatpakAppId
) {

    public enum BrowserType {
        EDGE("Microsoft Edge"),
        CHROME("Google Chrome"),
        CHROMIUM("Chromium"),
        FIREFOX("Mozilla Firefox"),
        SYSTEM_DEFAULT("System Default (xdg-open)"),
        EMBEDDED_WEBVIEW("Embedded In-App WebView");

        private final String label;

        BrowserType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static BrowserInfo embeddedWebView() {
        return new BrowserInfo(BrowserType.EMBEDDED_WEBVIEW, "Embedded In-App WebView", null, false, false, null);
    }

    public static BrowserInfo systemDefault() {
        return new BrowserInfo(BrowserType.SYSTEM_DEFAULT, "System Default Browser", null, false, false, null);
    }
}
