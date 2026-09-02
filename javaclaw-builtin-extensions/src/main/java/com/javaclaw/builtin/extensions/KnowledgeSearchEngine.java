package com.javaclaw.builtin.extensions;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingPurpose;

/** 对当前 Generation 执行关键词与同指纹向量检索，不把正文提升为系统指令。 */
final class KnowledgeSearchEngine {
    private static final int MAXIMUM_EXCERPTS = 3;
    private static final int EXCERPT_CHARACTERS = 396;
    private static final double MINIMUM_SEMANTIC_SCORE = 0.55;

    private final EmbeddingPort embeddings;

    KnowledgeSearchEngine(EmbeddingPort embeddings) {
        this.embeddings = Objects.requireNonNull(embeddings, "embeddings");
    }

    KnowledgeContracts.SearchResult search(
            KnowledgeIndexSnapshot snapshot, KnowledgeContracts.SearchRequest request, CancellationToken cancellation) {
        Optional<EmbeddingBatch> query = queryEmbedding(snapshot, request.query(), cancellation);
        boolean fallback = hasHybrid(snapshot) && query.isEmpty();
        List<ScoredMatch> matches = snapshot.entries().stream()
                .filter(entry -> request.mediaTypes().isEmpty()
                        || request.mediaTypes()
                                .contains(entry.source().attachment().mediaType()))
                .map(entry -> score(entry, request.query(), query))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingDouble(ScoredMatch::score)
                        .reversed()
                        .thenComparing(match -> match.entry().source().updatedAt(), Comparator.reverseOrder())
                        .thenComparing(match -> match.entry().source().id()))
                .limit(request.limit())
                .toList();
        boolean fingerprintMismatch = query.isPresent()
                && snapshot.entries().stream()
                        .filter(entry -> entry.generation().retrievalMode() == KnowledgeContracts.RetrievalMode.HYBRID)
                        .anyMatch(entry -> !entry.generation()
                                .embeddingFingerprint()
                                .orElseThrow()
                                .equals(query.orElseThrow().fingerprint()));
        return new KnowledgeContracts.SearchResult(
                matches.stream().map(ScoredMatch::contract).toList(), fallback || fingerprintMismatch);
    }

    private Optional<EmbeddingBatch> queryEmbedding(
            KnowledgeIndexSnapshot snapshot, String query, CancellationToken cancellation) {
        if (!hasHybrid(snapshot)) {
            return Optional.empty();
        }
        try {
            EmbeddingBatch batch = embeddings.embed(List.of(query), EmbeddingPurpose.QUERY, cancellation);
            if (batch.vectors().size() != 1) {
                return Optional.empty();
            }
            return Optional.of(batch);
        } catch (TurnCancelledException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            cancellation.throwIfCancelled();
            return Optional.empty();
        }
    }

    private Optional<ScoredMatch> score(
            KnowledgeIndexSnapshot.Entry entry, String query, Optional<EmbeddingBatch> queryEmbedding) {
        List<ChunkScore> chunks = entry.chunks().stream()
                .map(chunk -> scoreChunk(entry.generation(), chunk, query, queryEmbedding))
                .sorted(Comparator.comparingDouble(ChunkScore::score).reversed())
                .toList();
        boolean metadata = contains(
                query, entry.source().title(), entry.source().attachment().mediaType());
        double keyword = chunks.stream().mapToDouble(ChunkScore::keyword).max().orElse(0);
        double semantic =
                chunks.stream().mapToDouble(ChunkScore::semantic).max().orElse(0);
        double score = Math.max(metadata ? 0.7 : 0, Math.max(keyword, semantic));
        if (score <= 0 || (!metadata && keyword <= 0 && semantic < MINIMUM_SEMANTIC_SCORE)) {
            return Optional.empty();
        }
        List<String> excerpts = chunks.stream()
                .filter(chunk -> chunk.keyword() > 0 || chunk.semantic() >= MINIMUM_SEMANTIC_SCORE)
                .limit(MAXIMUM_EXCERPTS)
                .map(chunk -> excerpt(chunk.chunk().text(), query, chunk.keyword() > 0))
                .toList();
        KnowledgeContracts.RetrievalMode mode = semantic >= MINIMUM_SEMANTIC_SCORE
                ? KnowledgeContracts.RetrievalMode.HYBRID
                : KnowledgeContracts.RetrievalMode.KEYWORD;
        return Optional.of(new ScoredMatch(entry, excerpts, clamp(score), mode));
    }

    private ChunkScore scoreChunk(
            KnowledgeContracts.Generation generation,
            KnowledgeContracts.Chunk chunk,
            String query,
            Optional<EmbeddingBatch> queryEmbedding) {
        double keyword = contains(query, chunk.text()) ? 0.8 : 0;
        double semantic = semantic(generation, chunk, queryEmbedding);
        return new ChunkScore(chunk, keyword, semantic, Math.max(keyword, semantic));
    }

    private double semantic(
            KnowledgeContracts.Generation generation,
            KnowledgeContracts.Chunk chunk,
            Optional<EmbeddingBatch> queryEmbedding) {
        if (generation.retrievalMode() != KnowledgeContracts.RetrievalMode.HYBRID
                || queryEmbedding.isEmpty()
                || chunk.vector().isEmpty()) {
            return 0;
        }
        EmbeddingBatch query = queryEmbedding.orElseThrow();
        if (!generation.embeddingFingerprint().orElseThrow().equals(query.fingerprint())) {
            return 0;
        }
        return clamp(
                (cosine(chunk.vector().orElseThrow(), query.vectors().getFirst().values()) + 1) / 2);
    }

    private static double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size()) {
            return -1;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return -1;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static String excerpt(String text, String query, boolean keywordMatch) {
        int match = keywordMatch ? text.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT)) : 0;
        int start = Math.max(0, match - 120);
        int end = Math.min(text.length(), start + EXCERPT_CHARACTERS);
        if (end - start < EXCERPT_CHARACTERS) {
            start = Math.max(0, end - EXCERPT_CHARACTERS);
        }
        String prefix = start == 0 ? "" : "…";
        String suffix = end == text.length() ? "" : "…";
        return prefix + text.substring(start, end).strip() + suffix;
    }

    private static boolean contains(String query, String... values) {
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasHybrid(KnowledgeIndexSnapshot snapshot) {
        return snapshot.entries().stream()
                .anyMatch(entry -> entry.generation().retrievalMode() == KnowledgeContracts.RetrievalMode.HYBRID);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record ChunkScore(KnowledgeContracts.Chunk chunk, double keyword, double semantic, double score) {}

    private record ScoredMatch(
            KnowledgeIndexSnapshot.Entry entry,
            List<String> excerpts,
            double score,
            KnowledgeContracts.RetrievalMode mode) {
        private ScoredMatch {
            excerpts = List.copyOf(excerpts);
        }

        KnowledgeContracts.SearchMatch contract() {
            return new KnowledgeContracts.SearchMatch(entry.source(), entry.generation(), excerpts, score, mode);
        }
    }
}
