package org.alaurie.jw365.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Locates executable files on the current process PATH. */
public final class ExecutableLocator {

    private ExecutableLocator() {
    }

    public static Optional<Path> findOnPath(String executableName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }

        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path candidate = Paths.get(dir, executableName);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return Optional.of(candidate.toAbsolutePath());
            }
        }
        return Optional.empty();
    }
}
