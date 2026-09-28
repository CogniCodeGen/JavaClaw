package com.javaclaw.memory;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.memory.embed.EmbeddingGateway;
import com.javaclaw.memory.embed.EmbeddingPurpose;
import com.javaclaw.memory.store.MemoryStore;

import java.util.HashMap;
import java.util.Map;

/** Read-only vector scores used to rank deferred memory candidates. */
final class DeferredEvidenceScorer {
    private DeferredEvidenceScorer() { }

    static Map<String, Double> score(MemoryStore store, EmbeddingGateway gate,
                                      AgentConfig settings, String query, int limit) {
        if (store == null || query == null || query.isBlank() || limit < 1) return Map.of();
        float[] vector;
        try { vector = gate.embed(query, EmbeddingPurpose.INTERACTIVE_RECALL); }
        catch (RuntimeException unavailable) { return Map.of(); }
        if (vector == null) return Map.of();
        Map<String, Double> scores = new HashMap<>();
        try {
            for (var hit : store.searchFacts(vector, limit, settings.getMemoryRecallThreshold())) {
                if (hit.entity().id != null) {
                    scores.put("fact:" + hit.entity().id, (double) hit.score());
                }
            }
            for (var hit : store.searchEpisodes(vector, limit,
                    settings.getMemoryRecallThreshold())) {
                if (hit.entity().id != null) {
                    scores.put("episode:" + hit.entity().id, (double) hit.score());
                }
            }
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
        return Map.copyOf(scores);
    }
}
