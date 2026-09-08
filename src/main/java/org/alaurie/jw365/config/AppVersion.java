package org.alaurie.jw365.config;

import java.io.InputStream;
import java.util.Properties;

/**
 * Provides the application version string loaded from embedded build properties or package metadata.
 */
public final class AppVersion {

    public static final String VERSION = loadVersion();

    private AppVersion() {
    }

    private static String loadVersion() {
        try (InputStream is = AppVersion.class.getResourceAsStream("/org/alaurie/jw365/version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                String ver = props.getProperty("version");
                if (ver != null && !ver.isBlank() && !ver.startsWith("$")) {
                    return ver.trim();
                }
            }
        } catch (Exception ignored) {
        }
        Package pkg = AppVersion.class.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null && !pkg.getImplementationVersion().isBlank()) {
            return pkg.getImplementationVersion().trim();
        }
        return "0.2.1";
    }
}
