package com.javaclaw.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceContextTest {

    @Test
    void normalizesAllPathsIntoAbsoluteSnapshot() {
        WorkspaceContext context = new WorkspaceContext(
                "workspace-a",
                Path.of("data/../data"),
                Path.of("data/workspace-data/workspace-a"),
                Path.of("data/browser/workspace-a"),
                Path.of("data/screenshots/workspace-a"),
                Path.of("data/logs/workspace-a"));

        assertEquals("workspace-a", context.workspaceId());
        assertTrue(context.globalDataRoot().isAbsolute());
        assertEquals(Path.of("data").toAbsolutePath().normalize(), context.globalDataRoot());
        assertTrue(context.dataRoot().isAbsolute());
        assertTrue(context.browserDir().isAbsolute());
        assertTrue(context.screenshotsDir().isAbsolute());
        assertTrue(context.logDir().isAbsolute());
    }

    @Test
    void rejectsBlankWorkspaceId() {
        Path path = Path.of("data");
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspaceContext(" ", path, path, path, path, path));
    }

    @Test
    void rejectsMissingPath() {
        Path path = Path.of("data");
        assertThrows(NullPointerException.class,
                () -> new WorkspaceContext("workspace-a", path, null, path, path, path));
    }
}
