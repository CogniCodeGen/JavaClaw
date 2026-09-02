package com.javaclaw.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

import org.springframework.ai.embedding.EmbeddingModel;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.EmbeddingPurpose;
import com.javaclaw.extension.spi.EmbeddingVector;

/** 把 Spring AI EmbeddingModel 收敛为 JavaClaw 的批次、取消和指纹契约。 */
final class SpringAiEmbeddingPort implements EmbeddingPort, AutoCloseable {
    private static final int MAXIMUM_BATCH_SIZE = 128;

    private final EmbeddingModel model;
    private final String fingerprint;
    private final OptionalInt expectedDimensions;
    private final AutoCloseable resource;

    SpringAiEmbeddingPort(
            EmbeddingModel model, String fingerprint, OptionalInt expectedDimensions, AutoCloseable resource) {
        this.model = Objects.requireNonNull(model, "model");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.expectedDimensions = Objects.requireNonNull(expectedDimensions, "expectedDimensions");
        this.resource = Objects.requireNonNull(resource, "resource");
    }

    @Override
    public EmbeddingBatch embed(List<String> texts, EmbeddingPurpose purpose, CancellationToken cancellation) {
        List<String> checked = checkedTexts(texts);
        Objects.requireNonNull(purpose, "purpose");
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        List<EmbeddingVector> vectors = new ArrayList<>();
        int dimensions = -1;
        for (int start = 0; start < checked.size(); start += MAXIMUM_BATCH_SIZE) {
            checkedCancellation.throwIfCancelled();
            int end = Math.min(checked.size(), start + MAXIMUM_BATCH_SIZE);
            List<float[]> response = model.embed(checked.subList(start, end));
            if (response.size() != end - start) {
                throw new IllegalStateException("Spring AI Embedding response count differs from request");
            }
            for (float[] vector : response) {
                EmbeddingVector converted = convert(vector);
                dimensions = requireDimensions(dimensions, converted.values().size());
                vectors.add(converted);
            }
        }
        checkedCancellation.throwIfCancelled();
        expectedDimensions.ifPresent(expected -> {
            if (expected != vectors.getFirst().values().size()) {
                throw new IllegalStateException("Embedding dimensions differ from Provider configuration");
            }
        });
        return new EmbeddingBatch(fingerprint, dimensions, vectors);
    }

    @Override
    public void close() throws Exception {
        resource.close();
    }

    private static List<String> checkedTexts(List<String> texts) {
        List<String> copied = List.copyOf(Objects.requireNonNull(texts, "texts"));
        if (copied.isEmpty() || copied.size() > 10_000) {
            throw new IllegalArgumentException("Embedding text batch size is invalid");
        }
        if (copied.stream().anyMatch(text -> text == null || text.isBlank())) {
            throw new IllegalArgumentException("Embedding texts must not be blank");
        }
        return copied;
    }

    private static EmbeddingVector convert(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("Spring AI Embedding returned an empty vector");
        }
        List<Double> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add((double) value);
        }
        return new EmbeddingVector(values);
    }

    private static int requireDimensions(int current, int next) {
        if (current >= 0 && current != next) {
            throw new IllegalStateException("Embedding response contains mixed dimensions");
        }
        return next;
    }
}
