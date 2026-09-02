package com.javaclaw.builtin.contracts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeContractsTest {
    private static final String DIGEST = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void importOnlyCarriesCoreAttachmentReferenceAndValidatedIndexOptions() {
        KnowledgeContracts.ImportRequest request = new KnowledgeContracts.ImportRequest(
                "guide",
                "Guide",
                attachment(),
                10_000,
                1_000,
                100,
                KnowledgeContracts.RetrievalPreference.EMBEDDING_PREFERRED);

        assertEquals(attachment(), request.attachment());
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeContracts.ImportRequest(
                        "guide",
                        "Guide",
                        attachment(),
                        10_000,
                        100,
                        100,
                        KnowledgeContracts.RetrievalPreference.KEYWORD_ONLY));
    }

    @Test
    void generationSeparatesHybridMetadataFromExplicitKeywordFallback() {
        KnowledgeContracts.Generation hybrid =
                generation(KnowledgeContracts.RetrievalMode.HYBRID, Optional.of("b".repeat(64)), 2, Optional.empty());
        KnowledgeContracts.Generation keyword = generation(
                KnowledgeContracts.RetrievalMode.KEYWORD,
                Optional.empty(),
                0,
                Optional.of(KnowledgeContracts.FallbackReason.EMBEDDING_UNAVAILABLE));

        assertEquals(2, hybrid.embeddingDimensions());
        assertEquals(
                KnowledgeContracts.FallbackReason.EMBEDDING_UNAVAILABLE,
                keyword.fallbackReason().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> generation(KnowledgeContracts.RetrievalMode.HYBRID, Optional.empty(), 0, Optional.empty()));
    }

    @Test
    void searchContractsDeepCopyResultsAndRejectInvalidScores() {
        List<String> excerpts = new ArrayList<>(List.of("first"));
        KnowledgeContracts.SearchMatch match = new KnowledgeContracts.SearchMatch(
                source(),
                generation(KnowledgeContracts.RetrievalMode.KEYWORD, Optional.empty(), 0, Optional.empty()),
                excerpts,
                0.8,
                KnowledgeContracts.RetrievalMode.KEYWORD);
        KnowledgeContracts.SearchResult result = new KnowledgeContracts.SearchResult(List.of(match), true);
        excerpts.add("mutated");

        assertEquals(List.of("first"), result.matches().getFirst().excerpts());
        assertTrue(result.embeddingFallback());
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeContracts.SearchMatch(
                        source(),
                        match.generation(),
                        List.of("excerpt"),
                        1.1,
                        KnowledgeContracts.RetrievalMode.KEYWORD));
        assertThrows(
                IllegalArgumentException.class, () -> new KnowledgeContracts.SearchRequest("query", Set.of(), 101));
    }

    @Test
    void workerProtocolRequiresExactVersionAndExactlyOneOutcome() {
        KnowledgeWorkerProtocol.Request request =
                new KnowledgeWorkerProtocol.Request(1, "APPLICATION/PDF", DIGEST, 128, 5_000);
        KnowledgeContracts.ExtractionResult extracted =
                new KnowledgeContracts.ExtractionResult(DIGEST, "pdfbox-3", "text");

        assertEquals("application/pdf", request.mediaType());
        assertEquals(
                extracted,
                KnowledgeWorkerProtocol.Response.success(extracted).result().orElseThrow());
        assertEquals(
                "EXTRACTION_FAILED",
                KnowledgeWorkerProtocol.Response.failure("EXTRACTION_FAILED")
                        .error()
                        .orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeWorkerProtocol.Request(2, "text/plain", DIGEST, 1, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> new KnowledgeWorkerProtocol.Response(Optional.of(extracted), Optional.of("FAILED")));
    }

    private static KnowledgeContracts.Source source() {
        return new KnowledgeContracts.Source("guide", 1, "Guide", attachment(), "generation-1", NOW);
    }

    private static KnowledgeContracts.Generation generation(
            KnowledgeContracts.RetrievalMode mode,
            Optional<String> fingerprint,
            int dimensions,
            Optional<KnowledgeContracts.FallbackReason> fallback) {
        return new KnowledgeContracts.Generation(
                "generation-1", 1, "guide", 1, DIGEST, "plain-v1", mode, fingerprint, dimensions, 1, 4, fallback, NOW);
    }

    private static AttachmentRef attachment() {
        return new AttachmentRef(DIGEST, "text/plain", "guide.txt", 4);
    }
}
