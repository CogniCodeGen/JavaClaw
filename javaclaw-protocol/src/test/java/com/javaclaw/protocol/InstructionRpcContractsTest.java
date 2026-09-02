package com.javaclaw.protocol;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.api.WorktreeId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstructionRpcContractsTest {
    @Test
    void 读请求只携带资源标识且不携带路径或正文() {
        WorkspaceId workspaceId = WorkspaceId.parse("61e9d496-0798-49d8-a58e-f2337058e382");
        WorktreeId worktreeId = WorktreeId.parse("5ce503bd-f36b-4466-a29e-d5477fa31966");
        CanonicalPayload payload = new CanonicalJson()
                .encode(new InstructionRpcContracts.ReadPayload(workspaceId, Optional.of(worktreeId)));

        assertEquals(
                new InstructionRpcContracts.ReadPayload(workspaceId, Optional.of(worktreeId)),
                new CanonicalJson().decode(payload, InstructionRpcContracts.ReadPayload.class));
        assertTrue(!payload.json().contains("root") && !payload.json().contains("content"));
    }

    @Test
    void 设置请求只允许安全basename并保留显式空值() {
        WorkspaceId workspaceId = WorkspaceId.parse("61e9d496-0798-49d8-a58e-f2337058e382");
        CanonicalJson json = new CanonicalJson();
        var enabled = new InstructionRpcContracts.SettingsUpdatePayload(workspaceId, Optional.of("PROJECT.md"));

        assertEquals(enabled, json.decode(json.encode(enabled), InstructionRpcContracts.SettingsUpdatePayload.class));
        assertEquals(
                Optional.empty(),
                json.decode(
                                json.encode(new InstructionRpcContracts.SettingsUpdatePayload(
                                        workspaceId, Optional.empty())),
                                InstructionRpcContracts.SettingsUpdatePayload.class)
                        .fallbackBasename());
        assertThrows(
                IllegalArgumentException.class,
                () -> new com.javaclaw.api.WorkspaceInstructionSettings(
                        workspaceId, Optional.of("../unsafe.md"), 1, java.time.Instant.parse("2026-09-01T00:00:00Z")));
    }
}
