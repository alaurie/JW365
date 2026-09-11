package org.alaurie.jw365.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    void testLogPruning(@org.junit.jupiter.api.io.TempDir Path logDir) throws Exception {
        Files.createDirectories(logDir);

        // Create 15 dummy session logs with distinct timestamps
        long baseMillis = System.currentTimeMillis() - 100_000;
        for (int i = 0; i < 15; i++) {
            Path file = logDir.resolve("session_test_prune_" + i + ".log");
            Files.writeString(file, "Log content " + i);
            Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochMilli(baseMillis + (i * 1000))));
        }

        XdgPaths.pruneOldLogs(logDir, 10);
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

    @Test
    @DisplayName("pruneOldLogs bounds total retained session log bytes")
    void testTotalLogRetention(@org.junit.jupiter.api.io.TempDir Path logDir) throws Exception {
        Files.createDirectories(logDir);
        byte[] content = new byte[4 * 1024 * 1024];
        try {
            for (int i = 0; i < 10; i++) {
                Files.write(logDir.resolve("session_test_size_" + i + ".log"), content);
            }
            XdgPaths.pruneOldLogs(logDir, 10);
            long total = 0;
            try (var stream = Files.list(logDir)) {
                total = stream
                    .filter(p -> p.getFileName().toString().startsWith("session_test_size_"))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
            }
            assertThat(total).isLessThanOrEqualTo(32L * 1024 * 1024);
        } finally {
            try (var stream = Files.list(logDir)) {
                stream.filter(p -> p.getFileName().toString().startsWith("session_test_size_"))
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
