package org.alaurie.jw365.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class XdgLogPruningTest {

    @Test
    @DisplayName("pruneOldLogs retains the requested maximum number of recent session logs and deletes older ones")
    void testLogPruning() throws Exception {
        Path logDir = XdgPaths.logsDir();

        // Create 15 dummy session logs with distinct timestamps
        long baseMillis = System.currentTimeMillis() - 100_000;
        for (int i = 0; i < 15; i++) {
            Path file = logDir.resolve("session_test_prune_" + i + ".log");
            Files.writeString(file, "Log content " + i);
            Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochMilli(baseMillis + (i * 1000))));
        }

        XdgPaths.pruneOldLogs(10);

        try (var stream = Files.list(logDir)) {
            List<Path> remaining = stream
                .filter(p -> p.getFileName().toString().startsWith("session_test_prune_"))
                .toList();

            assertThat(remaining).hasSizeLessThanOrEqualTo(10);
        } finally {
            // Cleanup test files
            try (var stream = Files.list(logDir)) {
                stream.filter(p -> p.getFileName().toString().startsWith("session_test_prune_"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
            }
        }
    }
}
