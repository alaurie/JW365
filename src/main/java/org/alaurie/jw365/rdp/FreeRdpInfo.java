package org.alaurie.jw365.rdp;

import java.nio.file.Path;

/**
 * Metadata and details of a detected FreeRDP executable.
 *
 * @param binaryPath     absolute path to binary or null if flatpak
 * @param flavor         the detected FreeRDP flavor
 * @param versionString  reported version output
 * @param isFlatpak      true if launched via flatpak
 * @param flatpakAppId   app ID if flatpak (e.g. "com.freerdp.FreeRDP")
 */
public record FreeRdpInfo(
    Path binaryPath,
    FreeRdpFlavor flavor,
    String versionString,
    boolean isFlatpak,
    String flatpakAppId
) {

    public String displayName() {
        if (isFlatpak) {
            return "Flatpak (" + flatpakAppId + ")";
        }
        if (binaryPath != null) {
            return binaryPath.getFileName().toString() + (versionString != null ? " (" + versionString.trim() + ")" : "");
        }
        return flavor.getDescription();
    }
}
