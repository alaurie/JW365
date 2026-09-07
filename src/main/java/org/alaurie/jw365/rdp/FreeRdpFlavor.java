package org.alaurie.jw365.rdp;

/**
 * FreeRDP client binary variant/flavor.
 */
public enum FreeRdpFlavor {
    SDL_FREERDP("sdl-freerdp", "SDL-based FreeRDP client"),
    XFREERDP3("xfreerdp3", "X11 FreeRDP 3.x client"),
    XFREERDP("xfreerdp", "X11 FreeRDP client"),
    WLFREERDP3("wlfreerdp3", "Wayland FreeRDP 3.x client"),
    WLFREERDP("wlfreerdp", "Wayland FreeRDP client"),
    FLATPAK("flatpak", "Flatpak sandboxed FreeRDP client"),
    CUSTOM("custom", "User-specified custom FreeRDP binary");

    private final String executableName;
    private final String description;

    FreeRdpFlavor(String executableName, String description) {
        this.executableName = executableName;
        this.description = description;
    }

    public String getExecutableName() {
        return executableName;
    }

    public String getDescription() {
        return description;
    }

    public static FreeRdpFlavor fromBinaryName(String name) {
        if (name == null) {
            return CUSTOM;
        }
        String lower = name.toLowerCase();
        if (lower.contains("sdl-freerdp")) return SDL_FREERDP;
        if (lower.contains("xfreerdp3")) return XFREERDP3;
        if (lower.contains("xfreerdp")) return XFREERDP;
        if (lower.contains("wlfreerdp3")) return WLFREERDP3;
        if (lower.contains("wlfreerdp")) return WLFREERDP;
        if (lower.contains("flatpak")) return FLATPAK;
        return CUSTOM;
    }
}
