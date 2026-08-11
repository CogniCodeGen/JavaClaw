package com.javaclaw.ui.javafx.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 知识库下拉菜单一次重建所需的不可变数据快照。 */
public record KnowledgeMenuSnapshot(
        boolean ragEnabled,
        List<Document> globalDocuments,
        List<Document> workspaceDocuments,
        Set<String> selectedDocumentNames) {

    public KnowledgeMenuSnapshot {
        globalDocuments = List.copyOf(Objects.requireNonNull(globalDocuments, "globalDocuments"));
        workspaceDocuments = List.copyOf(
                Objects.requireNonNull(workspaceDocuments, "workspaceDocuments"));
        selectedDocumentNames = Set.copyOf(
                Objects.requireNonNull(selectedDocumentNames, "selectedDocumentNames"));
    }

    public static KnowledgeMenuSnapshot ragDisabled() {
        return new KnowledgeMenuSnapshot(false, List.of(), List.of(), Set.of());
    }

    public int documentCount() {
        return globalDocuments.size() + workspaceDocuments.size();
    }

    /** 菜单展示用的稳定文档标识及其片段数。 */
    public record Document(String name, int chunks) {
        public Document {
            Objects.requireNonNull(name, "name");
        }
    }
}
