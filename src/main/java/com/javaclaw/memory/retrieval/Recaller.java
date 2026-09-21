package com.javaclaw.memory.retrieval;

import com.javaclaw.config.AgentConfig;
import com.javaclaw.memory.correction.CorrectionEngine;
import com.javaclaw.memory.correction.CorrectionTurnContext;
import com.javaclaw.memory.embed.EmbeddingGateway;
import com.javaclaw.memory.embed.EmbeddingPurpose;
import com.javaclaw.memory.model.Episode;
import com.javaclaw.memory.model.Fact;
import com.javaclaw.memory.model.Persona;
import com.javaclaw.memory.store.MemoryStore;
import com.javaclaw.util.TextSimilarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded hybrid retrieval over explicitly selected graphs, including unindexed raw evidence. */
public class Recaller {
    private final MemoryStore store;
    private final EmbeddingGateway gate;
    private final AgentConfig settings;

    public Recaller(MemoryStore store, EmbeddingGateway gate, AgentConfig settings) {
        this.store = store;
        this.gate = gate;
        this.settings = settings;
    }

    public String recall(String query) {
        return recallGraphs(List.of(store), gate, settings, query, settings.getMemoryRecallTopK());
    }

    public static String recallGraphs(List<MemoryStore> stores, EmbeddingGateway embeddings,
                                      AgentConfig settings, String query, int topK) {
        int limit = Math.max(1, Math.min(50, topK));
        int episodeLimit = Math.max(1, settings.getMemoryRecallEpisodes());
        int budget = Math.max(256, settings.getMemoryRecallMaxChars());
        float[] vector;
        try { vector = query == null || query.isBlank() ? null
                : embeddings.embed(query, EmbeddingPurpose.INTERACTIVE_RECALL); }
        catch (RuntimeException ignored) { vector = null; }
        StringBuilder body = new StringBuilder();
        Set<String> emittedFacts = new LinkedHashSet<>();
        Set<String> emittedEpisodes = new LinkedHashSet<>();
        for (int graphIndex = 0; graphIndex < stores.size(); graphIndex++) {
            MemoryStore store = stores.get(graphIndex);
            if (store == null || !store.isOpen()) continue;
            Persona persona = store.getPersona();
            if (persona != null && persona.content != null) append(body, "<persona>\n"
                    + persona.content + "\n</persona>\n", budget);
            var corrections = CorrectionEngine.selectRelevant(store.allCorrections(), query, 6);
            if (!corrections.isEmpty()) append(body,
                    new CorrectionTurnContext(corrections, null).toPrompt(), budget);
        }
        int graphNumber = 0;
        for (MemoryStore store : stores) {
            String graphLabel = graphNumber++ == 0 ? "当前图谱" : "工作区个人习惯";
            if (store == null || !store.isOpen()) continue;
            GraphEvidence evidenceSnapshot = evidence(store, vector, limit, episodeLimit,
                    settings.getMemoryRecallThreshold());
            List<Fact> allFacts = evidenceSnapshot.allFacts();
            List<Episode> allEpisodes = evidenceSnapshot.allEpisodes();
            Map<Fact, Double> facts = evidenceSnapshot.facts();
            Map<Episode, Double> episodes = evidenceSnapshot.episodes();
            for (Fact fact : allFacts) {
                double lexical = similarity(query, fact.text);
                if (lexical > 0 || fact.pinned || fact.userAsserted) {
                    facts.merge(fact, Math.max(lexical, fact.userAsserted ? .2 : .1), Math::max);
                }
            }
            for (Episode episode : allEpisodes) {
                double lexical = similarity(query, episode.userInput + " " + episode.assistantReply);
                if (lexical > 0) episodes.merge(episode, lexical, Math::max);
            }
            List<Fact> rankedFacts = facts.entrySet().stream()
                    .sorted(Map.Entry.<Fact, Double>comparingByValue().reversed())
                    .limit(limit).map(Map.Entry::getKey).toList();
            // One-hop entity and source traversal stays strictly inside this graph.
            Set<String> entities = new LinkedHashSet<>();
            for (Fact fact : rankedFacts) {
                if (fact.about != null) fact.about.forEach(e -> { if (e != null) entities.add(e.id); });
                if (fact.source != null) episodes.putIfAbsent(fact.source, .8);
            }
            for (Fact fact : allFacts) {
                if (fact.about != null && fact.about.stream().anyMatch(e -> e != null && entities.contains(e.id))) {
                    facts.putIfAbsent(fact, .15);
                }
            }
            for (var hit : facts.entrySet().stream()
                    .sorted(Map.Entry.<Fact, Double>comparingByValue().reversed()).limit(limit).toList()) {
                Fact fact = hit.getKey();
                if (!emittedFacts.add(fact.text)) continue;
                String source = fact.source == null ? "" : "；来源 " + fact.source.evidenceKey();
                append(body, "- [" + graphLabel + source + "] " + clip(fact.text, 1000) + "\n", budget);
            }
            // Short follow-ups and an unavailable embedding service still see recent source turns.
            if (episodes.isEmpty() || vector == null || (query != null && query.strip().length() < 8)) {
                allEpisodes.stream().sorted(Comparator.comparingLong((Episode e) -> e.timestamp).reversed())
                        .limit(episodeLimit).forEach(e -> episodes.putIfAbsent(e, .05));
            }
            for (var hit : episodes.entrySet().stream()
                    .sorted(Map.Entry.<Episode, Double>comparingByValue().reversed()
                            .thenComparing(e -> -e.getKey().timestamp)).limit(episodeLimit).toList()) {
                Episode episode = hit.getKey();
                if (!emittedEpisodes.add(episode.evidenceKey())) continue;
                String evidence = "<history_evidence source=\"" + escape(episode.evidenceKey()) + "\">\n"
                        + "用户：" + clip(episode.userInput, 450) + "\n"
                        + "当时回复：" + clip(episode.assistantReply, 1200) + "\n"
                        + (episode.toolTraceJson == null ? "" : "工具依据：" + clip(episode.toolTraceJson, 500) + "\n")
                        + "</history_evidence>\n";
                append(body, evidence, budget);
            }
        }
        return body.isEmpty() ? "" : "\n\n<loaded_context>\n" + body + "</loaded_context>\n";
    }

    private static GraphEvidence evidence(MemoryStore store, float[] vector, int factLimit,
                                          int episodeLimit, double threshold) {
        var snapshot = new GraphEvidence(new ArrayList<>(), new ArrayList<>(),
                new LinkedHashMap<>(), new LinkedHashMap<>());
        // 暂存迁移先删除再加入索引；两个集合必须共享写入锁，防止漏掉已持久化原文。
        // 向量已在锁外计算；这里只复制本地图谱和索引结果。
        store.withProjectionLock(() -> {
            snapshot.allFacts().addAll(store.allFacts());
            store.allPendingFacts().stream().filter(Recaller::verifiedPending)
                    .forEach(snapshot.allFacts()::add);
            snapshot.allFacts().removeIf(f -> f.superseded || f.contested);
            snapshot.allEpisodes().addAll(store.allEpisodes());
            snapshot.allEpisodes().addAll(store.allPendingEpisodes());
            if (vector != null) {
                try {
                    for (var hit : store.searchFacts(vector, factLimit, threshold))
                        snapshot.facts().put(hit.entity(), (double) hit.score());
                    for (var hit : store.searchEpisodes(vector, episodeLimit, threshold))
                        snapshot.episodes().put(hit.entity(), (double) hit.score());
                } catch (RuntimeException ignored) { /* 原始证据仍可用于词法召回。 */ }
            }
        });
        return snapshot;
    }

    private record GraphEvidence(List<Fact> allFacts, List<Episode> allEpisodes,
                                 Map<Fact, Double> facts, Map<Episode, Double> episodes) {}

    private static boolean verifiedPending(Fact fact) {
        return fact.userAsserted || fact.userEdited || "HABIT_REVIEW".equals(fact.sourceKind)
                || "DISTILLED".equals(fact.sourceKind);
    }

    private static double similarity(String query, String text) {
        if (query == null || query.isBlank() || text == null || text.isBlank()) return 0;
        String q = query.strip().toLowerCase(java.util.Locale.ROOT);
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (t.contains(q)) return 1;
        double score = TextSimilarity.bigramJaccard(q, t);
        return score >= .04 ? score : 0;
    }

    private static void append(StringBuilder target, String value, int budget) {
        if (target.length() + value.length() <= budget) target.append(value);
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
