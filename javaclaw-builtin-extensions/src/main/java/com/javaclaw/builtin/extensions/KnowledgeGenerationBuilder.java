package com.javaclaw.builtin.extensions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.TurnCancelledException;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPurpose;
import com.javaclaw.extension.spi.EmbeddingUnavailableException;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.extension.spi.VersionedDocument;

/** 完成 Attachment 解析、分块、Embedding 降级和 Generation 原子激活。 */
final class KnowledgeGenerationBuilder {
    private static final ExtensionId ID = new ExtensionId(BuiltinExtensionIds.KNOWLEDGE);

    private final ExtensionJobRuntimeContext context;

    KnowledgeGenerationBuilder(ExtensionJobRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    KnowledgeContracts.Generation build(
            ExtensionJob job,
            KnowledgeJobContracts.FrozenImport frozen,
            KnowledgeJobContracts.BuildIntent intent,
            CancellationToken cancellation)
            throws Exception {
        Optional<KnowledgeContracts.Generation> recovered = recover(job, frozen, intent);
        if (recovered.isPresent()) {
            return recovered.orElseThrow();
        }
        KnowledgeContracts.ExtractionResult extraction = extract(job, frozen, cancellation);
        requireDigest(frozen.request().attachment().digest(), extraction.digest());
        List<String> texts = KnowledgeChunker.split(
                extraction.text(),
                frozen.request().chunkCharacters(),
                frozen.request().overlapCharacters());
        IndexedChunks indexed = index(texts, frozen.request().retrievalPreference(), cancellation);
        return persist(job, frozen, intent, extraction, indexed);
    }

    private Optional<KnowledgeContracts.Generation> recover(
            ExtensionJob job, KnowledgeJobContracts.FrozenImport frozen, KnowledgeJobContracts.BuildIntent intent)
            throws Exception {
        return context.managedStore()
                .inTransaction(
                        ID,
                        transaction -> transaction
                                .get(KnowledgeCollections.generations(job.workspaceId()), intent.generationId())
                                .map(document -> {
                                    KnowledgeContracts.Generation generation = context.payloads()
                                            .decode(document.payload(), KnowledgeContracts.Generation.class);
                                    KnowledgeContracts.Source source =
                                            requireSource(transaction, job, frozen.expectedSourceRevision() + 1);
                                    if (!source.activeGenerationId().equals(generation.id())
                                            || !generation
                                                    .attachmentDigest()
                                                    .equals(frozen.request()
                                                            .attachment()
                                                            .digest())) {
                                        throw new IllegalStateException(
                                                "Knowledge Generation recovery state is inconsistent");
                                    }
                                    return generation;
                                }));
    }

    private KnowledgeContracts.ExtractionResult extract(
            ExtensionJob job, KnowledgeJobContracts.FrozenImport frozen, CancellationToken cancellation)
            throws Exception {
        cancellation.throwIfCancelled();
        KnowledgeContracts.ExtractionRequest request = new KnowledgeContracts.ExtractionRequest(
                frozen.request().attachment(), frozen.request().maxCharacters());
        var response = context.services()
                .invoke(new IsolatedServiceInvocation(
                        ID,
                        job.workspaceId(),
                        frozen.permissions(),
                        KnowledgeContracts.EXTRACTION_SERVICE,
                        context.payloads().encode(request),
                        cancellation));
        return context.payloads().decode(response, KnowledgeContracts.ExtractionResult.class);
    }

    private IndexedChunks index(
            List<String> texts, KnowledgeContracts.RetrievalPreference preference, CancellationToken cancellation)
            throws Exception {
        if (preference == KnowledgeContracts.RetrievalPreference.KEYWORD_ONLY) {
            return IndexedChunks.keyword(texts, Optional.empty());
        }
        try {
            EmbeddingBatch batch = context.embeddings().embed(texts, EmbeddingPurpose.DOCUMENT, cancellation);
            if (batch.vectors().size() != texts.size()) {
                throw new IllegalStateException("Embedding response count differs from Knowledge chunks");
            }
            return IndexedChunks.hybrid(texts, batch);
        } catch (TurnCancelledException cancelled) {
            throw cancelled;
        } catch (EmbeddingUnavailableException unavailable) {
            return IndexedChunks.keyword(texts, Optional.of(KnowledgeContracts.FallbackReason.EMBEDDING_UNAVAILABLE));
        } catch (Exception failure) {
            cancellation.throwIfCancelled();
            return IndexedChunks.keyword(texts, Optional.of(KnowledgeContracts.FallbackReason.EMBEDDING_FAILED));
        }
    }

    private KnowledgeContracts.Generation persist(
            ExtensionJob job,
            KnowledgeJobContracts.FrozenImport frozen,
            KnowledgeJobContracts.BuildIntent intent,
            KnowledgeContracts.ExtractionResult extraction,
            IndexedChunks indexed)
            throws Exception {
        return context.managedStore().inTransaction(ID, transaction -> {
            requireSourceRevision(transaction, job, frozen.expectedSourceRevision());
            KnowledgeContracts.Generation generation = generation(job, frozen, intent, extraction, indexed);
            putChunks(transaction, job, frozen.request().id(), generation.id(), indexed);
            transaction.put(
                    KnowledgeCollections.generations(job.workspaceId()),
                    generation.id(),
                    0,
                    context.payloads().encode(generation));
            KnowledgeContracts.Source source = new KnowledgeContracts.Source(
                    frozen.request().id(),
                    generation.sourceRevision(),
                    frozen.request().title(),
                    frozen.request().attachment(),
                    generation.id(),
                    generation.createdAt());
            transaction.put(
                    KnowledgeCollections.sources(job.workspaceId()),
                    source.id(),
                    frozen.expectedSourceRevision(),
                    context.payloads().encode(source));
            return generation;
        });
    }

    private KnowledgeContracts.Generation generation(
            ExtensionJob job,
            KnowledgeJobContracts.FrozenImport frozen,
            KnowledgeJobContracts.BuildIntent intent,
            KnowledgeContracts.ExtractionResult extraction,
            IndexedChunks indexed) {
        return new KnowledgeContracts.Generation(
                intent.generationId(),
                1,
                frozen.request().id(),
                Math.addExact(frozen.expectedSourceRevision(), 1),
                extraction.digest(),
                extraction.parserFingerprint(),
                indexed.mode(),
                indexed.embeddingFingerprint(),
                indexed.embeddingDimensions(),
                indexed.chunks().size(),
                extraction.text().length(),
                indexed.fallbackReason(),
                context.clock().instant());
    }

    private void putChunks(
            ExtensionTransaction transaction,
            ExtensionJob job,
            String sourceId,
            String generationId,
            IndexedChunks indexed) {
        String collection = KnowledgeCollections.chunks(job.workspaceId());
        for (int index = 0; index < indexed.chunks().size(); index++) {
            KnowledgeContracts.Chunk chunk = new KnowledgeContracts.Chunk(
                    generationId,
                    sourceId,
                    index,
                    indexed.chunks().get(index).text(),
                    indexed.chunks().get(index).vector());
            transaction.put(
                    collection,
                    KnowledgeCollections.chunkKey(generationId, index),
                    0,
                    context.payloads().encode(chunk));
        }
    }

    private void requireSourceRevision(ExtensionTransaction transaction, ExtensionJob job, long expectedRevision) {
        Optional<VersionedDocument> current =
                transaction.get(KnowledgeCollections.sources(job.workspaceId()), job.definitionId());
        if (expectedRevision == 0 && current.isPresent()) {
            throw new IllegalArgumentException("Knowledge source was created after Job submission");
        }
        if (expectedRevision > 0 && current.map(VersionedDocument::revision).orElse(-1L) != expectedRevision) {
            throw new IllegalArgumentException("Knowledge source revision changed before Generation activation");
        }
    }

    private KnowledgeContracts.Source requireSource(
            ExtensionTransaction transaction, ExtensionJob job, long expectedRevision) {
        VersionedDocument document = transaction
                .get(KnowledgeCollections.sources(job.workspaceId()), job.definitionId())
                .orElseThrow(() -> new IllegalStateException("Knowledge activated source is missing"));
        KnowledgeContracts.Source source =
                context.payloads().decode(document.payload(), KnowledgeContracts.Source.class);
        if (document.revision() != expectedRevision || source.revision() != expectedRevision) {
            throw new IllegalStateException("Knowledge activated source revision differs from Job");
        }
        return source;
    }

    private static void requireDigest(String expected, String actual) {
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("Knowledge Worker digest differs from Core Attachment");
        }
    }

    private record IndexedChunk(String text, Optional<List<Double>> vector) {
        private IndexedChunk {
            text = Objects.requireNonNull(text, "text");
            vector = Objects.requireNonNull(vector, "vector").map(List::copyOf);
        }
    }

    private record IndexedChunks(
            List<IndexedChunk> chunks,
            KnowledgeContracts.RetrievalMode mode,
            Optional<String> embeddingFingerprint,
            int embeddingDimensions,
            Optional<KnowledgeContracts.FallbackReason> fallbackReason) {
        private IndexedChunks {
            chunks = List.copyOf(chunks);
            Objects.requireNonNull(mode, "mode");
            embeddingFingerprint = Objects.requireNonNull(embeddingFingerprint, "embeddingFingerprint");
            fallbackReason = Objects.requireNonNull(fallbackReason, "fallbackReason");
        }

        static IndexedChunks keyword(List<String> texts, Optional<KnowledgeContracts.FallbackReason> fallbackReason) {
            return new IndexedChunks(
                    texts.stream()
                            .map(text -> new IndexedChunk(text, Optional.empty()))
                            .toList(),
                    KnowledgeContracts.RetrievalMode.KEYWORD,
                    Optional.empty(),
                    0,
                    fallbackReason);
        }

        static IndexedChunks hybrid(List<String> texts, EmbeddingBatch batch) {
            List<IndexedChunk> chunks = new ArrayList<>();
            for (int index = 0; index < texts.size(); index++) {
                chunks.add(new IndexedChunk(
                        texts.get(index), Optional.of(batch.vectors().get(index).values())));
            }
            return new IndexedChunks(
                    chunks,
                    KnowledgeContracts.RetrievalMode.HYBRID,
                    Optional.of(batch.fingerprint()),
                    batch.dimensions(),
                    Optional.empty());
        }
    }
}
