package com.javaclaw.infrastructure.knowledge;

import com.javaclaw.agent.expert.KnowledgeExpert;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.spi.DeferredContextCandidate;
import com.javaclaw.framework.spi.DeferredContextDigest;
import com.javaclaw.framework.spi.DeferredContextSource;

import java.util.List;
import java.util.Objects;

/** Read-only RAG chunk source with stable store IDs and content-hash version checks. */
public final class KnowledgeDeferredContextSource implements DeferredContextSource {
    private final KnowledgeExpert expert;

    public KnowledgeDeferredContextSource(KnowledgeExpert expert) {
        this.expert = Objects.requireNonNull(expert, "expert");
    }

    @Override public String id() { return "knowledge"; }
    @Override public String description() { return "Enabled workspace and global knowledge chunks"; }
    @Override public String group() { return "knowledge"; }
    @Override public PermissionSet requiredPermissions() { return PermissionSet.of("tool.read"); }

    @Override
    public List<DeferredContextCandidate> search(RunRequest request, String query, int limit) {
        if (limit < 1 || query == null || query.isBlank()) return List.of();
        int configured = request == null ? limit : com.javaclaw.framework.api.CapabilityRuntime
                .configuration(request, "knowledge.rag").path("topK").asInt(8);
        return expert.searchDeferred(query, Math.min(Math.min(limit, configured), 256)).stream()
                .filter(hit -> hit.id() != null && !hit.id().isBlank())
                .map(hit -> {
                    String summary = hit.docName() + " (" + hit.scope() + "): "
                            + Objects.requireNonNullElse(hit.summary(), "");
                    if (summary.length() > 240) summary = summary.substring(0, 240) + "…";
                    return new DeferredContextCandidate(hit.id(), hit.version(),
                            summary, PermissionSet.of("tool.read"));
                }).toList();
    }

    @Override
    public String fetch(RunRequest request, String candidateId, String version) {
        String body = expert.fetchDeferredContext(candidateId);
        if (body == null) throw new IllegalStateException("knowledge chunk no longer available: " + candidateId);
        if (!DeferredContextDigest.sha256(body).equals(version)) {
            throw new IllegalStateException("knowledge chunk version changed: " + candidateId);
        }
        return body;
    }
}
