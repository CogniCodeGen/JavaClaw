package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorktreeRpcContractsTest {
    private static final WorkspaceId WORKSPACE_ID = new WorkspaceId(new UUID(0, 1));
    private static final WorktreeId WORKTREE_ID = new WorktreeId(new UUID(0, 2));

    @Test
    void recoveryCenterContractsNormalizeAndProtectDangerousActions() {
        WorktreeRpcContracts.ListPayload list = new WorktreeRpcContracts.ListPayload(WORKSPACE_ID, true);
        WorktreeRpcContracts.InterruptPayload interrupt =
                new WorktreeRpcContracts.InterruptPayload(WORKTREE_ID, " stop ");
        WorktreeRpcContracts.CleanupPayload cleanup =
                new WorktreeRpcContracts.CleanupPayload(WORKTREE_ID, WorktreeRpcContracts.CLEANUP_CONFIRMATION);

        assertEquals(WORKSPACE_ID, list.workspaceId());
        assertEquals("stop", interrupt.reason());
        assertEquals(WORKTREE_ID, cleanup.worktreeId());
        assertThrows(IllegalArgumentException.class, () -> new WorktreeRpcContracts.CleanupPayload(WORKTREE_ID, "yes"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorktreeRpcContracts.InterruptPayload(WORKTREE_ID, "x".repeat(501)));
        assertThrows(NullPointerException.class, () -> new WorktreeRpcContracts.ReadPayload(null));
        assertThrows(NullPointerException.class, () -> new WorktreeRpcContracts.ListResult(null));
        assertTrue(new WorktreeRpcContracts.ListResult(List.of()).worktrees().isEmpty());
    }

    @Test
    void methodCatalogOnlyExposesRecoveryActionsAndSchemaIsV2Only() throws IOException {
        String methods = read("/schema/methods-v2.json");
        String schema = read("/schema/worktree-v2.schema.json");

        assertTrue(methods.contains("\"worktree/read\""));
        assertTrue(methods.contains("\"worktree/patch/export\""));
        assertTrue(methods.contains("\"worktree/backup\""));
        assertTrue(methods.contains("\"worktree/cleanup\""));
        assertFalse(methods.contains("\"worktree/create\""));
        assertFalse(methods.contains("\"worktree/delete\""));
        assertTrue(schema.contains("CLEANUP WORKTREE"));
        assertFalse(schema.contains("merge"));
    }

    private static String read(String resource) throws IOException {
        try (var input = WorktreeRpcContractsTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("missing resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
