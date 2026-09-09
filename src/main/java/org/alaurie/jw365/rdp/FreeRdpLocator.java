package org.alaurie.jw365.rdp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Discovers and validates installed FreeRDP binaries on the host system.
 */
public final class FreeRdpLocator {

    private static final List<String> CANDIDATE_NAMES = List.of(
        "sdl-freerdp3",
        "sdl-freerdp",
        "xfreerdp3",
        "xfreerdp",
        "wlfreerdp3",
        "wlfreerdp"
    );

    private static final java.util.regex.Pattern VERSION_PATTERN =
        java.util.regex.Pattern.compile("(?:version\\s+)?v?(\\d+\\.\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private FreeRdpLocator() {
    }

    /**
     * Locates FreeRDP using legacy automatic behavior.
     */
    public static Optional<FreeRdpInfo> locate(String customPath) {
        return locate("AUTO", customPath);
    }

    /**
     * Locates FreeRDP according to source preference: AUTO, SYSTEM, FLATPAK, or CUSTOM.
     */
    public static Optional<FreeRdpInfo> locate(String source, String customPath) {
        String preference = source == null || source.isBlank() ? "AUTO" : source.toUpperCase();
        if (preference.equals("FLATPAK")) {
            return checkFlatpak();
        }
        if (preference.equals("CUSTOM")) {
            return inspectCustomPath(customPath);
        }
        if (preference.equals("AUTO") && customPath != null && !customPath.isBlank()) {
            Optional<FreeRdpInfo> custom = inspectCustomPath(customPath);
            if (custom.isPresent()) {
                return custom;
            }
        }
        if (preference.equals("AUTO")) {
            Optional<FreeRdpInfo> flatpak = checkFlatpak();
            if (flatpak.isPresent()) {
                return flatpak;
            }
        }
        return locateNative();
    }

    private static Optional<FreeRdpInfo> inspectCustomPath(String customPath) {
        if (customPath == null || customPath.isBlank()) {
            return Optional.empty();
        }
        if (customPath.equalsIgnoreCase("flatpak") || customPath.contains("com.freerdp.FreeRDP")) {
            return checkFlatpak();
        }
        try {
            Path p = Paths.get(customPath);
            if (Files.isExecutable(p)) {
                return Optional.of(inspectBinary(p, FreeRdpFlavor.fromBinaryName(p.getFileName().toString())));
            }
        } catch (RuntimeException ignored) {
        }
        return Optional.empty();
    }

    private static Optional<FreeRdpInfo> locateNative() {
        String envPath = System.getenv("JW365_FREERDP");
        if (envPath != null && !envPath.isBlank()) {
            Optional<FreeRdpInfo> configured = inspectCustomPath(envPath);
            if (configured.isPresent()) {
                return configured;
            }
        }
        for (String candidate : CANDIDATE_NAMES) {
            Optional<Path> found = findExecutableOnPath(candidate);
            if (found.isPresent()) {
                Path bin = found.get();
                return Optional.of(inspectBinary(bin, FreeRdpFlavor.fromBinaryName(candidate)));
            }
        }
        return Optional.empty();
    }

    /**
     * Finds all available FreeRDP installations on the host system.
     */
    public static List<FreeRdpInfo> findAll() {
        List<FreeRdpInfo> list = new ArrayList<>();

        for (String candidate : CANDIDATE_NAMES) {
            Optional<Path> found = findExecutableOnPath(candidate);
            found.ifPresent(path -> list.add(inspectBinary(path, FreeRdpFlavor.fromBinaryName(candidate))));
        }

        checkFlatpak().ifPresent(list::add);
        return list;
    }

    private static Optional<Path> findExecutableOnPath(String executableName) {
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

    private static FreeRdpInfo inspectBinary(Path path, FreeRdpFlavor flavor) {
        String version = extractVersion(List.of(path.toString(), "--version"));
        if (version == null) {
            version = extractVersion(List.of(path.toString(), "/version"));
        }
        return new FreeRdpInfo(path, flavor, version != null ? version : "FreeRDP", false, null);
    }

    private static Optional<FreeRdpInfo> checkFlatpak() {
        Optional<Path> flatpakBin = findExecutableOnPath("flatpak");
        if (flatpakBin.isEmpty()) {
            return Optional.empty();
        }

        try {
            Process p = new ProcessBuilder("flatpak", "info", "com.freerdp.FreeRDP").start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (finished && p.exitValue() == 0) {
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String version = "v3.31.1";
                var matcher = VERSION_PATTERN.matcher(out);
                if (matcher.find()) {
                    version = "v" + matcher.group(1);
                }
                return Optional.of(new FreeRdpInfo(null, FreeRdpFlavor.FLATPAK, version, true, "com.freerdp.FreeRDP"));
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private static String extractVersion(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (finished) {
                String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (out.isBlank()) {
                    out = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                }
                for (String line : out.lines().toList()) {
                    var matcher = VERSION_PATTERN.matcher(line);
                    if (matcher.find()) {
                        return "v" + matcher.group(1);
                    }
                }
                if (!out.isBlank()) {
                    return out.lines().findFirst().orElse(null);
                }
            } else {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
