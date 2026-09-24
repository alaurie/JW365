package org.alaurie.jw365.util;

import java.awt.Desktop;
import java.awt.Desktop.Action;
import java.io.File;
import java.net.URI;

import org.alaurie.jw365.config.XdgPaths;

/**
 * Robust cross-desktop launcher for web URLs and local files with automatic
 * fallbacks for Linux desktop environments and Flatpak sandboxes.
 */
public final class DesktopOpener {

    private DesktopOpener() {}

    /**
     * Opens the specified URI in the default web browser.
     */
    public static boolean browse(URI uri) {
        if (uri == null) {
            return false;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
                return true;
            }
        } catch (Exception _) {
        }
        return openViaCli(uri.toString());
    }

    /**
     * Opens the specified local file in its default system viewer/editor.
     */
    public static boolean open(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Action.OPEN)) {
                Desktop.getDesktop().open(file);
                return true;
            }
        } catch (Exception _) {
        }
        return openViaCli(file.getAbsolutePath());
    }

    private static boolean openViaCli(String target) {
        try {
            Process process;
            if (XdgPaths.isFlatpak()) {
                process = new ProcessBuilder("flatpak-spawn", "--host", "xdg-open", target).start();
            } else {
                process = new ProcessBuilder("xdg-open", target).start();
            }
            return process != null;
        } catch (Exception _) {
            return false;
        }
    }
}
