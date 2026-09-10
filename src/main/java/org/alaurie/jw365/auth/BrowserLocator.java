package org.alaurie.jw365.auth;

import org.alaurie.jw365.util.ExecutableLocator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Discovers and launches installed Linux browsers with priority for Microsoft Edge (for M365 Work Profile SSO).
 */
public final class BrowserLocator {

    private static final List<String> EDGE_CANDIDATES = List.of(
        "microsoft-edge-stable",
        "microsoft-edge",
        "microsoft-edge-beta",
        "microsoft-edge-dev"
    );

    private static final List<String> CHROME_CANDIDATES = List.of(
        "google-chrome-stable",
        "google-chrome"
    );

    private static final List<String> CHROMIUM_CANDIDATES = List.of(
        "chromium",
        "chromium-browser"
    );

    private static final List<String> FIREFOX_CANDIDATES = List.of(
        "firefox",
        "firefox-esr"
    );

    private BrowserLocator() {
    }

    /**
     * Locates Microsoft Edge on the host system.
     */
    public static Optional<BrowserInfo> findEdge() {
        for (String candidate : EDGE_CANDIDATES) {
            Optional<Path> found = ExecutableLocator.findOnPath(candidate);
            if (found.isPresent()) {
                return Optional.of(new BrowserInfo(
                    BrowserInfo.BrowserType.EDGE,
                    "Microsoft Edge (Work Profile SSO)",
                    found.get(),
                    true,
                    false,
                    null
                ));
            }
        }

        // Check flatpak
        if (isEdgeFlatpakInstalled()) {
            return Optional.of(new BrowserInfo(
                BrowserInfo.BrowserType.EDGE,
                "Microsoft Edge (Flatpak)",
                null,
                true,
                true,
                "com.microsoft.Edge"
            ));
        }

        return Optional.empty();

    }

    /**
     * Finds the recommended browser for authentication, preferring Microsoft Edge for M365 SSO.
     */
    public static BrowserInfo findBestBrowser() {
        return findEdge().orElseGet(BrowserInfo::systemDefault);
    }

    /**
     * Finds all supported browsers installed on the host system.
     */
    public static List<BrowserInfo> findAllInstalled() {
        List<BrowserInfo> list = new ArrayList<>();

        findEdge().ifPresent(list::add);

        for (String c : CHROME_CANDIDATES) {
            Optional<Path> found = ExecutableLocator.findOnPath(c);
            if (found.isPresent()) {
                list.add(new BrowserInfo(BrowserInfo.BrowserType.CHROME, "Google Chrome", found.get(), false, false, null));
                break;
            }
        }

        for (String c : CHROMIUM_CANDIDATES) {
            Optional<Path> found = ExecutableLocator.findOnPath(c);
            if (found.isPresent()) {
                list.add(new BrowserInfo(BrowserInfo.BrowserType.CHROMIUM, "Chromium", found.get(), false, false, null));
                break;
            }
        }

        for (String c : FIREFOX_CANDIDATES) {
            Optional<Path> found = ExecutableLocator.findOnPath(c);
            if (found.isPresent()) {
                list.add(new BrowserInfo(BrowserInfo.BrowserType.FIREFOX, "Mozilla Firefox", found.get(), false, false, null));
                break;
            }
        }

        list.add(BrowserInfo.systemDefault());
        list.add(BrowserInfo.embeddedWebView());

        return list;
    }


    private static boolean isEdgeFlatpakInstalled() {
        Optional<Path> flatpakBin = ExecutableLocator.findOnPath("flatpak");
        if (flatpakBin.isEmpty()) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("flatpak", "info", "com.microsoft.Edge").start();
            return p.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
