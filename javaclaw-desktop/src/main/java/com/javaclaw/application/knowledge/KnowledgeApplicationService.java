package com.javaclaw.application.knowledge;

import java.nio.file.Path;
import java.util.List;

/**
 * 当前工作区知识库的唯一业务入口。
 *
 * <p>查询、导入、检索与重建可能阻塞，调用方必须显式提交到工作区托管 I/O 执行器。
 * 返回值均为不可变快照；导入允许部分成功，取消不会回滚已经完成的文件。</p>
 */
public interface KnowledgeApplicationService {

    Snapshot snapshot();

    SearchResult search(String query);

    ImportResult importFiles(List<Path> files, Scope scope);

    ImportResult importText(String title, String text, Scope scope);

    Snapshot setDocumentEnabled(String documentName, boolean enabled);

    Snapshot setAllEnabled(boolean enabled, Scope scope);

    Snapshot deleteDocument(String documentName);

    Snapshot clear();

    ReindexResult rebuildIndex();

    Settings saveChunkSettings(int chunkSize, int chunkOverlap);

    AutoCloseable observeHealth(HealthListener listener);

    enum Scope { ALL, WORKSPACE, GLOBAL }

    enum HealthStatus { HEALTHY, CHECKING, DEGRADED, UNAVAILABLE, UNCONFIGURED }

    @FunctionalInterface
    interface HealthListener {
        void onHealthChanged(Health health);
    }

    record Snapshot(
            boolean enabled,
            String initializationError,
            String workspaceName,
            Settings settings,
            Health health,
            List<Document> documents) {
        public Snapshot {
            initializationError = text(initializationError);
            workspaceName = text(workspaceName).isBlank() ? "默认工作区" : workspaceName.strip();
            settings = java.util.Objects.requireNonNull(settings, "settings");
            health = java.util.Objects.requireNonNull(health, "health");
            documents = List.copyOf(documents == null ? List.of() : documents);
        }

        public int totalChunks() {
            return documents.stream().mapToInt(Document::chunkCount).sum();
        }

        public long enabledCount() {
            return documents.stream().filter(Document::enabled).count();
        }

        public long count(Scope scope) {
            if (scope == null || scope == Scope.ALL) return documents.size();
            return documents.stream().filter(document -> document.scope() == scope).count();
        }
    }

    record Document(
            String name,
            Scope scope,
            boolean enabled,
            int chunkCount,
            String importedAt,
            String summary,
            List<String> previews) {
        public Document {
            name = text(name);
            scope = scope == null ? Scope.WORKSPACE : scope;
            importedAt = text(importedAt);
            summary = text(summary);
            previews = List.copyOf(previews == null ? List.of() : previews);
        }
    }

    record Settings(
            String provider,
            String baseUrl,
            String model,
            int dimensions,
            int retrieveLimit,
            int chunkSize,
            int chunkOverlap) {
        public Settings {
            provider = text(provider);
            baseUrl = text(baseUrl);
            model = text(model);
        }
    }

    record Health(HealthStatus status, String error) {
        public Health {
            status = status == null ? HealthStatus.UNCONFIGURED : status;
            error = text(error);
        }
    }

    record SearchHit(String documentName, Scope scope, double score, String content) {
        public SearchHit {
            documentName = text(documentName);
            scope = scope == null ? Scope.WORKSPACE : scope;
            content = text(content);
        }
    }

    record SearchResult(String query, List<SearchHit> hits) {
        public SearchResult {
            query = text(query);
            hits = List.copyOf(hits == null ? List.of() : hits);
        }
    }

    record ImportResult(Snapshot snapshot, int succeeded, int failed, List<String> failures) {
        public ImportResult {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
            failures = List.copyOf(failures == null ? List.of() : failures);
        }
    }

    record ReindexResult(Snapshot snapshot, int rebuiltChunks) {
        public ReindexResult {
            snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    private static String text(String value) { return value == null ? "" : value; }
}
