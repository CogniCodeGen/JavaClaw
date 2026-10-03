package com.javaclaw.config;

import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagedDataPathTest {
    @TempDir Path temporary;

    @Test
    void startupFailsWhenScreenshotBucketPointsOutsideDataRoot() throws Exception {
        DataRoot root = new DataRoot(temporary.resolve("data")).prepare();
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Files.createSymbolicLink(root.path().resolve("screenshots"), outside);

        assertThrows(RuntimeException.class, () -> ApplicationContexts.createRoot(root));
        try (var entries = Files.list(outside)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test
    void workspaceSwitchRollsBackWhenTargetLogDirectoryIsSymlinked() throws Exception {
        DataRoot root = new DataRoot(temporary.resolve("data")).prepare();
        try (var context = ApplicationContexts.createRoot(root)) {
            WorkspaceManager workspaces = context.getBean(WorkspaceManager.class);
            String previous = workspaces.getCurrentWorkspaceId();
            String target = workspaces.createWorkspace("target").getId();
            Path outside = Files.createDirectories(temporary.resolve("outside"));
            Files.createSymbolicLink(root.path().resolve("logs").resolve(target), outside);

            assertFalse(workspaces.switchWorkspace(target));
            assertEquals(previous, workspaces.getCurrentWorkspaceId());
            try (var entries = Files.list(outside)) {
                assertTrue(entries.findAny().isEmpty());
            }
        }
    }
}
