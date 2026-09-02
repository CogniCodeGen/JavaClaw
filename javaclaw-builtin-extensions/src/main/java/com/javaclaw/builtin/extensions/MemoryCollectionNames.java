package com.javaclaw.builtin.extensions;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.extension.spi.ExtensionRequest;

/** 生成 Memory 私有集合名，确保所有领域记录都按 Workspace 隔离。 */
final class MemoryCollectionNames {
    private static final String MEMORIES = "memories.";
    private static final String PROPOSALS = "proposals.";
    private static final String SETTINGS = "settings.";

    private MemoryCollectionNames() {}

    static String memories(ExtensionRequest request) {
        return memories(request.workspaceId());
    }

    static String memories(WorkspaceId workspaceId) {
        return MEMORIES + workspaceId;
    }

    static String proposals(ExtensionRequest request) {
        return proposals(request.workspaceId());
    }

    static String proposals(WorkspaceId workspaceId) {
        return PROPOSALS + workspaceId;
    }

    static String settings(ExtensionRequest request) {
        return settings(request.workspaceId());
    }

    static String settings(WorkspaceId workspaceId) {
        return SETTINGS + workspaceId;
    }
}
