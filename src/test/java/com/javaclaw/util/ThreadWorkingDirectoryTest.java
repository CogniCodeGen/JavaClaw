package com.javaclaw.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ThreadWorkingDirectoryTest {
    @Test void concurrentTurnsResolveRelativeFilesAgainstTheirOwnDirectoryWithoutWideningHostAccess()
            throws Exception {
        Path root = ProjectAccessPolicy.projectRoot();
        Path fixture = Files.createTempDirectory(root.resolve("target"), "thread-cwd-");
        Path first = Files.createDirectory(fixture.resolve("a"));
        Path second = Files.createDirectory(fixture.resolve("b"));
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = tasks.submit(() -> ProjectAccessPolicy.withWorkingDirectory(first.toString(),
                    () -> ProjectAccessPolicy.resolveProjectPath("result.txt")));
            var right = tasks.submit(() -> ProjectAccessPolicy.withWorkingDirectory(second.toString(),
                    () -> ProjectAccessPolicy.resolveProjectPath("result.txt")));
            assertEquals(first.resolve("result.txt"), left.get());
            assertEquals(second.resolve("result.txt"), right.get());
            assertEquals(root, ProjectAccessPolicy.workingDirectory());
            assertThrows(SecurityException.class, () -> ProjectAccessPolicy.withWorkingDirectory(
                    first.toString(), () -> ProjectAccessPolicy.resolveProjectPath(root.getParent().resolve("outside").toString())));
            assertThrows(SecurityException.class, () -> ProjectAccessPolicy.withWorkingDirectory(
                    root.getParent().toString(), () -> "not allowed"));
            assertEquals(root, ProjectAccessPolicy.workingDirectory());
        } finally {
            Files.deleteIfExists(first);
            Files.deleteIfExists(second);
            Files.deleteIfExists(fixture);
        }
    }
}
