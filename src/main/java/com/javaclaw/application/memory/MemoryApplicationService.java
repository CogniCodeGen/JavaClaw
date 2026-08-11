package com.javaclaw.application.memory;

import com.javaclaw.memory.graph.MemoryGraph;

import java.nio.file.Path;
import java.util.List;

/**
 * 工作区记忆中心的唯一业务入口。
 *
 * <p>查询会访问对象存储，嵌入探测、回填、索引重建和导出会阻塞 I/O；调用方必须显式提交
 * 到托管 I/O 执行器。返回值均为不可变快照，不得跨工作区 Context 生命周期缓存。
 * 取消仅阻止尚未开始的后续步骤，已经完成的持久化写入不会回滚。</p>
 */
public interface MemoryApplicationService {

    Snapshot snapshot();

    OperationResult probeAndRefill();

    OperationResult refillPending();

    MemoryGraph graph();

    OperationResult addFact(AddFactCommand command);

    OperationResult editFact(EditFactCommand command);

    OperationResult toggleFactPin(String factId);

    OperationResult restoreFact(String factId);

    OperationResult deleteFacts(List<String> factIds);

    OperationResult reindexDocument(String documentName);

    OperationResult deleteDocument(String documentName);

    OperationResult savePersona(PersonaDraft persona);

    String personaMarkdown(PersonaDraft persona);

    void exportPersona(Path target, PersonaDraft persona);

    OperationResult revokeCorrection(String correctionId);

    OperationResult deleteCorrection(String correctionId);

    record Snapshot(
            Statistics statistics,
            List<FactItem> facts,
            List<EpisodeItem> episodes,
            List<EntityItem> entities,
            List<KnowledgeDocument> documents,
            PersonaDraft persona,
            List<CorrectionItem> corrections,
            List<ChangeItem> changes,
            EmbeddingState embedding) {
        public Snapshot {
            statistics = statistics == null ? Statistics.empty() : statistics;
            facts = List.copyOf(facts == null ? List.of() : facts);
            episodes = List.copyOf(episodes == null ? List.of() : episodes);
            entities = List.copyOf(entities == null ? List.of() : entities);
            documents = List.copyOf(documents == null ? List.of() : documents);
            persona = persona == null ? PersonaDraft.empty() : persona;
            corrections = List.copyOf(corrections == null ? List.of() : corrections);
            changes = List.copyOf(changes == null ? List.of() : changes);
            embedding = embedding == null ? new EmbeddingState("", 0) : embedding;
        }
    }

    record Statistics(long recalls, long factHits, long factsDistilled, long factsMerged) {
        public static Statistics empty() { return new Statistics(0, 0, 0, 0); }
    }

    record FactItem(
            String id,
            String section,
            String text,
            long updatedAt,
            int hitCount,
            int mergeCount,
            boolean userEdited,
            boolean userAsserted,
            boolean pinned,
            boolean superseded,
            boolean contested,
            boolean pending,
            String sourceEpisodeId,
            List<String> entityNames) {
        public FactItem {
            id = normalizeText(id);
            section = normalizeText(section).isBlank() ? "其它" : normalizeText(section);
            text = normalizeText(text);
            sourceEpisodeId = normalizeText(sourceEpisodeId);
            entityNames = List.copyOf(entityNames == null ? List.of() : entityNames);
        }
    }

    record EpisodeItem(
            String id,
            String userInput,
            String assistantReply,
            String toolTraceJson,
            long timestamp,
            boolean pending,
            int derivedFactCount) {
        public EpisodeItem {
            id = normalizeText(id);
            userInput = normalizeText(userInput);
            assistantReply = normalizeText(assistantReply);
            toolTraceJson = normalizeText(toolTraceJson);
        }

        public boolean hasToolTrace() { return !toolTraceJson.isBlank(); }
    }

    record EntityItem(String id, String name, String type, int factCount) {
        public EntityItem {
            id = normalizeText(id);
            name = normalizeText(name);
            type = normalizeText(type).isBlank() ? "其它" : normalizeText(type);
        }
    }

    record KnowledgeDocument(
            String name, long chunkCount, long characterCount, String importedAt) {
        public KnowledgeDocument {
            name = normalizeText(name);
            importedAt = normalizeText(importedAt).isBlank() ? "—" : normalizeText(importedAt);
        }
    }

    record PersonaDraft(
            String identity, String tone, List<String> preferences, List<String> taboos) {
        public PersonaDraft {
            identity = normalizeText(identity).strip();
            tone = normalizeText(tone).strip();
            tone = tone.isBlank() ? "简洁直接" : tone;
            preferences = clean(preferences);
            taboos = clean(taboos);
        }

        public static PersonaDraft empty() {
            return new PersonaDraft("", "简洁直接", List.of(), List.of());
        }
    }

    record CorrectionItem(
            String id,
            String type,
            String scope,
            String status,
            String wrongClaim,
            String correctClaim,
            String sourceInput,
            long timestamp,
            boolean effective) {
        public CorrectionItem {
            id = normalizeText(id);
            type = normalizeText(type);
            scope = normalizeText(scope);
            status = normalizeText(status);
            wrongClaim = normalizeText(wrongClaim);
            correctClaim = normalizeText(correctClaim);
            sourceInput = normalizeText(sourceInput);
        }
    }

    record ChangeItem(
            long timestamp, String operation, String type, String targetId, String detail) {
        public ChangeItem {
            operation = normalizeText(operation);
            type = normalizeText(type);
            targetId = normalizeText(targetId);
            detail = normalizeText(detail);
        }
    }

    record EmbeddingState(String error, int pendingCount) {
        public EmbeddingState { error = normalizeText(error); }
        public boolean healthy() { return error.isBlank(); }
        public boolean degraded() { return pendingCount > 0 || !healthy(); }
        public boolean canRefill() { return pendingCount > 0 && healthy(); }
    }

    record AddFactCommand(String section, String text) {}

    record EditFactCommand(String id, String text) {}

    record OperationResult(Snapshot snapshot, int affected, String message) {
        public OperationResult {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            message = normalizeText(message);
        }
    }

    private static List<String> clean(List<String> source) {
        if (source == null) return List.of();
        return source.stream().map(MemoryApplicationService::normalizeText)
                .map(String::strip).filter(value -> !value.isBlank()).toList();
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value;
    }
}
