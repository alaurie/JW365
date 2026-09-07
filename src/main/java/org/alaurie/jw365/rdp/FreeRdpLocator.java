package org.alaurie.jw365.rdp;

import java.io.File;
import java.io.IOException;
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

    private FreeRdpLocator() {
    }

    /**
     * Locates the best available FreeRDP installation on the host system.
     *
     * @param customPath optional custom path configured by the user
     * @return Optional containing FreeRdpInfo if found
     */
    public static Optional<FreeRdpInfo> locate(String customPath) {
        // 1. Check custom configured path
        if (customPath != null && !customPath.isBlank()) {
            Path p = Paths.get(customPath);
            if (Files.isExecutable(p)) {
                return Optional.of(inspectBinary(p, FreeRdpFlavor.fromBinaryName(p.getFileName().toString())));
            }
        }

        // 2. Check environment variable JW365_FREERDP
        String envPath = System.getenv("JW365_FREERDP");
        if (envPath != null && !envPath.isBlank()) {
            Path p = Paths.get(envPath);
            if (Files.isExecutable(p)) {
                return Optional.of(inspectBinary(p, FreeRdpFlavor.fromBinaryName(p.getFileName().toString())));
            }
        }

        // 3. Search PATH for native candidates
        for (String candidate : CANDIDATE_NAMES) {
            Optional<Path> found = findExecutableOnPath(candidate);
            if (found.isPresent()) {
                Path bin = found.get();
                return Optional.of(inspectBinary(bin, FreeRdpFlavor.fromBinaryName(candidate)));
            }
        }

        // 4. Check Flatpak
        Optional<FreeRdpInfo> flatpak = checkFlatpak();
        if (flatpak.isPresent()) {
            return flatpak;
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
                return Optional.of(new FreeRdpInfo(null, FreeRdpFlavor.FLATPAK, "FreeRDP Flatpak", true, "com.freerdp.FreeRDP"));
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    private static final java.util.regex.Pattern VERSION_PATTERN =
        java.util.regex.Pattern.compile("(?:version\\s+)?v?(\\d+\\.\\d+(?:\\.\\d+)?)", java.util.regex.Pattern.CASE_INSENSITIVE);

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
