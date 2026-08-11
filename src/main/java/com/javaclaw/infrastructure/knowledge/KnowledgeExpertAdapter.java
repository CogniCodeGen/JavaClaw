package com.javaclaw.infrastructure.knowledge;

import com.javaclaw.agent.expert.KnowledgeExpert;
import com.javaclaw.agent.model.ToolResponse;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Document;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Health;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.HealthListener;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.HealthStatus;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.Scope;
import com.javaclaw.application.knowledge.KnowledgeApplicationService.SearchHit;
import com.javaclaw.application.knowledge.KnowledgePort;
import com.javaclaw.memory.embed.EmbeddingHealthSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Adapts the existing RAG runtime to the knowledge application boundary. */
public final class KnowledgeExpertAdapter implements KnowledgePort {

    private static final int PREVIEW_LIMIT = 3;
    private final KnowledgeExpert expert;

    public KnowledgeExpertAdapter(KnowledgeExpert expert) {
        this.expert = Objects.requireNonNull(expert, "expert");
    }

    @Override public boolean enabled() { return expert.isRagEnabled(); }

    @Override public String initializationError() { return expert.ragInitializationError(); }

    @Override public Health health() { return toHealth(expert.embeddingHealth()); }

    @Override
    public AutoCloseable observeHealth(HealthListener listener) {
        Objects.requireNonNull(listener, "listener");
        return expert.onEmbeddingHealthChanged(snapshot -> listener.onHealthChanged(toHealth(snapshot)));
    }

    @Override
    public List<Document> documents() {
        List<Document> result = new ArrayList<>();
        appendDocuments(result, KnowledgeExpert.Scope.GLOBAL, Scope.GLOBAL);
        appendDocuments(result, KnowledgeExpert.Scope.WORKSPACE, Scope.WORKSPACE);
        return List.copyOf(result);
    }

    @Override
    public List<SearchHit> search(String query, int limit) {
        return expert.searchTest(query, limit).stream()
                .map(hit -> new SearchHit(hit.docName(), scope(hit.scope()),
                        hit.score(), hit.content()))
                .toList();
    }

    @Override
    public boolean importFile(Path file, Scope scope) {
        return ToolResponse.isSuccess(expert.importFile(file.toString(), expertScope(scope)));
    }

    @Override
    public boolean importText(String title, String text, Scope scope) {
        return ToolResponse.isSuccess(expert.importText(text, title, expertScope(scope)));
    }

    @Override public void setDocumentEnabled(String name, boolean enabled) {
        expert.setDocEnabled(name, enabled);
    }

    @Override public void setAllEnabled(boolean enabled, Scope scope) {
        expert.setAllEnabled(enabled, scope == null ? null : expertScope(scope));
    }

    @Override public int deleteDocument(String name) { return expert.deleteDocument(name); }

    @Override
    public int clear() {
        int removed = expert.getTotalChunkCount();
        expert.knowledge_clear();
        return removed;
    }

    @Override public int rebuildIndex() { return expert.reindexAll(); }

    private void appendDocuments(
            List<Document> target,
            KnowledgeExpert.Scope expertScope,
            Scope scope) {
        for (String name : expert.getDocumentNames(expertScope)) {
            List<String> previews = expert.getDocumentChunkPreviews(name, PREVIEW_LIMIT);
            String summary = previews.isEmpty() ? "" : summarize(previews.getFirst());
            target.add(new Document(name, scope, expert.isDocEnabled(name),
                    expert.getDocumentChunkCount(name), expert.getDocumentImportTime(name),
                    summary, previews));
        }
    }

    private static KnowledgeExpert.Scope expertScope(Scope scope) {
        return scope == Scope.GLOBAL
                ? KnowledgeExpert.Scope.GLOBAL : KnowledgeExpert.Scope.WORKSPACE;
    }

    private static Scope scope(String value) {
        return "GLOBAL".equalsIgnoreCase(value) ? Scope.GLOBAL : Scope.WORKSPACE;
    }

    private static Health toHealth(EmbeddingHealthSnapshot snapshot) {
        if (snapshot == null) return new Health(HealthStatus.UNCONFIGURED, "");
        HealthStatus status;
        try {
            status = HealthStatus.valueOf(snapshot.status().name());
        } catch (RuntimeException unsupported) {
            status = HealthStatus.UNAVAILABLE;
        }
        return new Health(status, snapshot.lastError());
    }

    private static String summarize(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return compact.length() <= 120 ? compact : compact.substring(0, 120) + "…";
    }
}
