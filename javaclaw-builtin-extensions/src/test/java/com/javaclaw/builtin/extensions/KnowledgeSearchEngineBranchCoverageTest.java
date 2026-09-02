package com.javaclaw.builtin.extensions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingVector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSearchEngineBranchCoverageTest {
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String OTHER_FINGERPRINT = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void keywordSearchCoversMetadataMediaFilterOrderingAndExcerptBounds() {
        String leading = "前缀".repeat(100) + "needle" + "后缀".repeat(160);
        String trailing = "开头".repeat(180) + "needle";
        KnowledgeIndexSnapshot snapshot = new KnowledgeIndexSnapshot(List.of(
                keywordEntry("older", "普通标题", "text/plain", NOW.minusSeconds(10), leading, "needle near start"),
                keywordEntry("newer", "needle 标题", "text/markdown", NOW, trailing, "another needle"),
                keywordEntry("media", "媒体命中", "application/needle", NOW.plusSeconds(10), "无关正文")));
        KnowledgeSearchEngine engine = new KnowledgeSearchEngine(failingEmbeddings());

        KnowledgeContracts.SearchResult all = engine.search(
                snapshot, new KnowledgeContracts.SearchRequest(" NEEDLE ", Set.of(), 10), new CancellationSource());
        KnowledgeContracts.SearchResult markdown = engine.search(
                snapshot,
                new KnowledgeContracts.SearchRequest("needle", Set.of("text/markdown"), 10),
                new CancellationSource());
        KnowledgeContracts.SearchResult absent = engine.search(
                snapshot,
                new KnowledgeContracts.SearchRequest("absent", Set.of("application/json"), 10),
                new CancellationSource());

        assertEquals(List.of("newer", "older", "media"), sourceIds(all));
        assertEquals(List.of("newer"), sourceIds(markdown));
        assertTrue(absent.matches().isEmpty());
        assertFalse(all.embeddingFallback());
        assertTrue(all.matches().stream()
                .allMatch(match -> match.retrievalMode() == KnowledgeContracts.RetrievalMode.KEYWORD));
        assertTrue(all.matches().stream()
                .flatMap(match -> match.excerpts().stream())
                .allMatch(value -> value.length() <= 400));
        assertTrue(all.matches().stream()
                .flatMap(match -> match.excerpts().stream())
                .anyMatch(value -> value.startsWith("…") || value.endsWith("…")));
    }

    @Test
    void hybridSearchCoversSemanticThresholdFingerprintAndVectorFailures() {
        KnowledgeIndexSnapshot snapshot = new KnowledgeIndexSnapshot(List.of(
                hybridEntry("same", NOW, FINGERPRINT, List.of(1.0, 0.0), "语义正文"),
                hybridEntry("opposite", NOW.minusSeconds(1), FINGERPRINT, List.of(-1.0, 0.0), "不命中"),
                hybridEntry("zero", NOW.minusSeconds(2), FINGERPRINT, List.of(0.0, 0.0), "不命中"),
                hybridEntry("short", NOW.minusSeconds(3), FINGERPRINT, List.of(1.0), "不命中"),
                hybridEntry("mismatch", NOW.minusSeconds(4), OTHER_FINGERPRINT, List.of(1.0, 0.0), "不命中"),
                keywordEntry("keyword", "普通标题", "text/plain", NOW.minusSeconds(5), "semantic query")));
        KnowledgeSearchEngine engine = new KnowledgeSearchEngine(embeddings(FINGERPRINT, List.of(1.0, 0.0)));

        KnowledgeContracts.SearchResult result = engine.search(
                snapshot,
                new KnowledgeContracts.SearchRequest("semantic query", Set.of(), 10),
                new CancellationSource());

        assertEquals(List.of("same", "keyword"), sourceIds(result));
        assertEquals(
                KnowledgeContracts.RetrievalMode.HYBRID,
                result.matches().getFirst().retrievalMode());
        assertEquals(1.0, result.matches().getFirst().score());
        assertTrue(result.embeddingFallback());
    }

    @Test
    void embeddingFailuresFallBackButCancellationRemainsVisible() {
        KnowledgeIndexSnapshot snapshot = new KnowledgeIndexSnapshot(
                List.of(hybridEntry("hybrid", NOW, FINGERPRINT, List.of(1.0, 0.0), "needle")));
        KnowledgeContracts.SearchRequest request = new KnowledgeContracts.SearchRequest("needle", Set.of(), 10);

        KnowledgeContracts.SearchResult wrongCardinality = new KnowledgeSearchEngine((texts, purpose, cancellation) ->
                        new EmbeddingBatch(
                                FINGERPRINT,
                                2,
                                List.of(
                                        new EmbeddingVector(List.of(1.0, 0.0)),
                                        new EmbeddingVector(List.of(0.0, 1.0)))))
                .search(snapshot, request, new CancellationSource());
        KnowledgeContracts.SearchResult failed =
                new KnowledgeSearchEngine(failingEmbeddings()).search(snapshot, request, new CancellationSource());

        CancellationSource cancelledByPort = new CancellationSource();
        cancelledByPort.cancel("用户取消");
        assertThrows(
                TurnCancelledException.class,
                () -> new KnowledgeSearchEngine((texts, purpose, cancellation) -> {
                            cancellation.throwIfCancelled();
                            throw new AssertionError("不可达");
                        })
                        .search(snapshot, request, cancelledByPort));

        CancellationSource cancelledAfterFailure = new CancellationSource();
        cancelledAfterFailure.cancel("撤销查询");
        assertThrows(
                TurnCancelledException.class,
                () -> new KnowledgeSearchEngine(failingEmbeddings()).search(snapshot, request, cancelledAfterFailure));

        assertTrue(wrongCardinality.embeddingFallback());
        assertTrue(failed.embeddingFallback());
        assertEquals(List.of("hybrid"), sourceIds(failed));
    }

    @Test
    void semanticExcerptIsBoundedToThreeChunksAndLimitIsApplied() {
        List<KnowledgeContracts.Chunk> chunks = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            chunks.add(chunk("generation-many", "many", index, "正文-" + index, Optional.of(List.of(1.0, 0.0))));
        }
        KnowledgeIndexSnapshot.Entry many =
                entry("many", "普通标题", "text/plain", NOW, hybridGeneration("many", FINGERPRINT, 5), chunks);
        KnowledgeIndexSnapshot.Entry second =
                hybridEntry("second", NOW.minusSeconds(1), FINGERPRINT, List.of(1.0, 0.0), "另一个正文");
        KnowledgeSearchEngine engine = new KnowledgeSearchEngine(embeddings(FINGERPRINT, List.of(1.0, 0.0)));

        KnowledgeContracts.SearchResult result = engine.search(
                new KnowledgeIndexSnapshot(List.of(many, second)),
                new KnowledgeContracts.SearchRequest("纯语义", Set.of(), 1),
                new CancellationSource());

        assertEquals(1, result.matches().size());
        assertEquals(3, result.matches().getFirst().excerpts().size());
        assertEquals("many", result.matches().getFirst().source().id());
    }

    private static List<String> sourceIds(KnowledgeContracts.SearchResult result) {
        return result.matches().stream().map(match -> match.source().id()).toList();
    }

    private static KnowledgeIndexSnapshot.Entry keywordEntry(
            String id, String title, String mediaType, Instant updatedAt, String... texts) {
        List<KnowledgeContracts.Chunk> chunks = java.util.stream.IntStream.range(0, texts.length)
                .mapToObj(index -> chunk("generation-" + id, id, index, texts[index], Optional.empty()))
                .toList();
        return entry(id, title, mediaType, updatedAt, keywordGeneration(id, chunks.size()), chunks);
    }

    private static KnowledgeIndexSnapshot.Entry hybridEntry(
            String id, Instant updatedAt, String fingerprint, List<Double> vector, String text) {
        return entry(
                id,
                "普通标题",
                "text/plain",
                updatedAt,
                hybridGeneration(id, fingerprint, 1),
                List.of(chunk("generation-" + id, id, 0, text, Optional.of(vector))));
    }

    private static KnowledgeIndexSnapshot.Entry entry(
            String id,
            String title,
            String mediaType,
            Instant updatedAt,
            KnowledgeContracts.Generation generation,
            List<KnowledgeContracts.Chunk> chunks) {
        AttachmentRef attachment = new AttachmentRef(digest(id), mediaType, id + ".txt", 20);
        KnowledgeContracts.Source source =
                new KnowledgeContracts.Source(id, 1, title, attachment, generation.id(), updatedAt);
        return new KnowledgeIndexSnapshot.Entry(source, generation, chunks);
    }

    private static KnowledgeContracts.Generation keywordGeneration(String id, int chunks) {
        return new KnowledgeContracts.Generation(
                "generation-" + id,
                1,
                id,
                1,
                digest(id),
                "plain-v1",
                KnowledgeContracts.RetrievalMode.KEYWORD,
                Optional.empty(),
                0,
                chunks,
                100,
                Optional.empty(),
                NOW);
    }

    private static KnowledgeContracts.Generation hybridGeneration(String id, String fingerprint, int chunks) {
        return new KnowledgeContracts.Generation(
                "generation-" + id,
                1,
                id,
                1,
                digest(id),
                "plain-v1",
                KnowledgeContracts.RetrievalMode.HYBRID,
                Optional.of(fingerprint),
                2,
                chunks,
                100,
                Optional.empty(),
                NOW);
    }

    private static KnowledgeContracts.Chunk chunk(
            String generationId, String sourceId, int index, String text, Optional<List<Double>> vector) {
        return new KnowledgeContracts.Chunk(generationId, sourceId, index, text, vector);
    }

    private static EmbeddingPort embeddings(String fingerprint, List<Double> values) {
        return (texts, purpose, cancellation) ->
                new EmbeddingBatch(fingerprint, values.size(), List.of(new EmbeddingVector(values)));
    }

    private static EmbeddingPort failingEmbeddings() {
        return (texts, purpose, cancellation) -> {
            throw new IllegalStateException("Provider 不可用");
        };
    }

    private static String digest(String value) {
        char first = (char) ('0' + Math.floorMod(value.hashCode(), 10));
        return String.valueOf(first).repeat(64);
    }
}
