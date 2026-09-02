package com.javaclaw.builtin.extensions;

import com.javaclaw.api.WorkspaceId;

/** Knowledge 托管存储集合与稳定键规则。 */
final class KnowledgeCollections {
    private KnowledgeCollections() {}

    static String sources(WorkspaceId workspaceId) {
        return "sources." + workspaceId;
    }

    static String generations(WorkspaceId workspaceId) {
        return "generations." + workspaceId;
    }

    static String chunks(WorkspaceId workspaceId) {
        return "chunks." + workspaceId;
    }

    static String chunkKey(String generationId, int index) {
        return generationId + "." + "%08d".formatted(index);
    }
}
