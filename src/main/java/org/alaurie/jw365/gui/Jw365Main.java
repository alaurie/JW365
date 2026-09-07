package org.alaurie.jw365.gui;

/**
 * Standard bootstrap launcher avoiding JavaFX runtime module enforcement on standard classpath execution
 * and establishing desktop integration properties for Wayland / X11 window managers.
 */
public final class Jw365Main {

    private Jw365Main() {
    }

    public static void main(String[] args) {
        // Configure native window manager / Wayland app_id and X11 WM_CLASS
        System.setProperty("jdk.gtk.name", "jw365");
        System.setProperty("sun.awt.datatransfer.appName", "jw365");

        Jw365App.main(args);
    }
}
