package com.javaclaw.system;

import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.platform.data.DataRoot;
import com.javaclaw.platform.spring.ApplicationContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandWhitelistManagerTest {

    @Test
    void entriesArePersistedAndReloadedPerWorkspace(@TempDir Path temporaryDirectory) {
        try (var root = ApplicationContexts.createRoot(
                new DataRoot(temporaryDirectory.resolve("data-v3")))) {
            WorkspaceManager workspaces = root.getBean(WorkspaceManager.class);
            CommandWhitelistManager whitelist = root.getBean(CommandWhitelistManager.class);
            String firstWorkspace = workspaces.getCurrentWorkspaceId();

            String id = whitelist.addEntry("git push", temporaryDirectory.toString());
            assertTrue(whitelist.isWhitelisted(
                    "git push origin main", temporaryDirectory.toString()));
            whitelist.incrementUseCount("git push origin main", temporaryDirectory.toString());
            assertEquals(1, whitelist.listEntries().getFirst().useCount());

            var second = workspaces.createWorkspace("第二工作区");
            assertTrue(workspaces.switchWorkspace(second.getId()));
            whitelist.reload();
            assertTrue(whitelist.listEntries().isEmpty());
            assertFalse(whitelist.isWhitelisted("git push", temporaryDirectory.toString()));

            assertTrue(workspaces.switchWorkspace(firstWorkspace));
            whitelist.reload();
            assertEquals(id, whitelist.listEntries().getFirst().id());
            assertEquals(1, whitelist.listEntries().getFirst().useCount());
        }
    }
}
