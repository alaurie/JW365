package org.alaurie.jw365.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/** Locates executable files on the current process PATH. */
public final class ExecutableLocator {

    private ExecutableLocator() {}

    public static Optional<Path> findOnPath(String executableName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }

        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir, executableName);
                if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                    return Optional.of(candidate.toAbsolutePath());
                }
            } catch (InvalidPathException | SecurityException _) {
            }
        }
        return Optional.empty();
    }
}
