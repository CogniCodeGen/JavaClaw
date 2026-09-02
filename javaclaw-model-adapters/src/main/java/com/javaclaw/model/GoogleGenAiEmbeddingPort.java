package com.javaclaw.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

import com.google.genai.Client;
import com.google.genai.types.EmbedContentConfig;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingPurpose;
import com.javaclaw.extension.spi.EmbeddingVector;

/** Google Gen AI SDK 的用途感知 Embedding 适配器。 */
final class GoogleGenAiEmbeddingPort implements EmbeddingPort, AutoCloseable {
    private final Client client;
    private final String model;
    private final String fingerprint;
    private final OptionalInt expectedDimensions;

    GoogleGenAiEmbeddingPort(Client client, String model, String fingerprint, OptionalInt expectedDimensions) {
        this.client = Objects.requireNonNull(client, "client");
        this.model = Objects.requireNonNull(model, "model");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.expectedDimensions = Objects.requireNonNull(expectedDimensions, "expectedDimensions");
    }

    @Override
    public EmbeddingBatch embed(List<String> texts, EmbeddingPurpose purpose, CancellationToken cancellation) {
        List<String> checked = List.copyOf(Objects.requireNonNull(texts, "texts"));
        if (checked.isEmpty()
                || checked.size() > 10_000
                || checked.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("Embedding texts must not be empty or blank");
        }
        EmbeddingPurpose checkedPurpose = Objects.requireNonNull(purpose, "purpose");
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        checkedCancellation.throwIfCancelled();
        EmbedContentConfig.Builder options = EmbedContentConfig.builder()
                .taskType(checkedPurpose == EmbeddingPurpose.DOCUMENT ? "RETRIEVAL_DOCUMENT" : "RETRIEVAL_QUERY")
                .autoTruncate(false);
        expectedDimensions.ifPresent(options::outputDimensionality);
        var response = client.models.embedContent(model, checked, options.build());
        List<com.google.genai.types.ContentEmbedding> embeddings = response.embeddings()
                .orElseThrow(() -> new IllegalStateException("Google Embedding response has no vectors"));
        if (embeddings.size() != checked.size()) {
            throw new IllegalStateException("Google Embedding response count differs from request");
        }
        List<EmbeddingVector> vectors = new ArrayList<>();
        int dimensions = -1;
        for (var embedding : embeddings) {
            List<Float> values = embedding
                    .values()
                    .orElseThrow(() -> new IllegalStateException("Google Embedding vector has no values"));
            EmbeddingVector vector =
                    new EmbeddingVector(values.stream().map(Float::doubleValue).toList());
            dimensions = requireDimensions(dimensions, vector.values().size());
            vectors.add(vector);
        }
        checkedCancellation.throwIfCancelled();
        int actualDimensions = dimensions;
        expectedDimensions.ifPresent(expected -> {
            if (expected != actualDimensions) {
                throw new IllegalStateException("Embedding dimensions differ from Provider configuration");
            }
        });
        return new EmbeddingBatch(fingerprint, dimensions, vectors);
    }

    @Override
    public void close() {
        client.close();
    }

    private static int requireDimensions(int current, int next) {
        if (current >= 0 && current != next) {
            throw new IllegalStateException("Embedding response contains mixed dimensions");
        }
        return next;
    }
}
