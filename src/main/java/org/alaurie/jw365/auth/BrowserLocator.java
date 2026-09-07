package org.alaurie.jw365.auth;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
            Optional<Path> found = findOnPath(candidate);
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
        if (isFlatpakInstalled("com.microsoft.Edge")) {
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
        Optional<BrowserInfo> edge = findEdge();
        if (edge.isPresent()) {
            return edge.get();
        }
        return BrowserInfo.systemDefault();
    }

    /**
     * Finds all supported browsers installed on the host system.
     */
    public static List<BrowserInfo> findAllInstalled() {
        List<BrowserInfo> list = new ArrayList<>();

        findEdge().ifPresent(list::add);

        for (String c : CHROME_CANDIDATES) {
            Optional<Path> found = findOnPath(c);
            if (found.isPresent()) {
                list.add(new BrowserInfo(BrowserInfo.BrowserType.CHROME, "Google Chrome", found.get(), false, false, null));
                break;
            }
        }

        for (String c : CHROMIUM_CANDIDATES) {
            Optional<Path> found = findOnPath(c);
            if (found.isPresent()) {
                list.add(new BrowserInfo(BrowserInfo.BrowserType.CHROMIUM, "Chromium", found.get(), false, false, null));
                break;
            }
        }

        for (String c : FIREFOX_CANDIDATES) {
            Optional<Path> found = findOnPath(c);
            if (found.isPresent()) {
                list.add(new BrowserInfo(BrowserInfo.BrowserType.FIREFOX, "Mozilla Firefox", found.get(), false, false, null));
                break;
            }
        }

        list.add(BrowserInfo.systemDefault());
        list.add(BrowserInfo.embeddedWebView());

        return list;
    }

    /**
     * Launches the specified browser to open the target URI.
     *
     * @param browser target browser info
     * @param uri     the URI to navigate to
     * @param appMode if true and browser is Chromium/Edge, opens as dedicated app window (--app=URL)
     */
    public static void launch(BrowserInfo browser, URI uri, boolean appMode) throws IOException {
        if (browser == null || browser.type() == BrowserInfo.BrowserType.SYSTEM_DEFAULT) {
            launchDefault(uri);
            return;
        }

        List<String> command = new ArrayList<>();

        if (browser.isFlatpak()) {
            command.add("flatpak");
            command.add("run");
            command.add(browser.flatpakAppId());
            command.add(uri.toString());
        } else if (browser.executablePath() != null) {
            command.add(browser.executablePath().toString());
            if (appMode && (browser.isEdge() || browser.type() == BrowserInfo.BrowserType.CHROME || browser.type() == BrowserInfo.BrowserType.CHROMIUM)) {
                command.add("--app=" + uri);
            } else {
                command.add("--new-window");
                command.add(uri.toString());
            }
        } else {
            launchDefault(uri);
            return;
        }

        new ProcessBuilder(command).start();
    }

    private static void launchDefault(URI uri) throws IOException {
        Optional<Path> xdgOpen = findOnPath("xdg-open");
        if (xdgOpen.isPresent()) {
            new ProcessBuilder(xdgOpen.get().toString(), uri.toString()).start();
            return;
        }

        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri);
            return;
        }

        throw new IOException("No supported mechanism to open browser for URI: " + uri);
    }

    private static Optional<Path> findOnPath(String executableName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }

        String[] dirs = pathEnv.split(File.pathSeparator);
        for (String dir : dirs) {
            Path p = Paths.get(dir, executableName);
            if (Files.isExecutable(p) && !Files.isDirectory(p)) {
                return Optional.of(p.toAbsolutePath());
            }
        }
        return Optional.empty();
    }

    private static boolean isFlatpakInstalled(String appId) {
        Optional<Path> flatpakBin = findOnPath("flatpak");
        if (flatpakBin.isEmpty()) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("flatpak", "info", appId).start();
            return p.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
