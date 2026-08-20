package com.javaclaw.framework.springai;

import com.javaclaw.inference.api.InferenceEmbeddingRequest;
import com.javaclaw.inference.api.LocalInferenceGateway;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 本地嵌入协议到 Spring AI EmbeddingModel 的隔离适配器。 */
public final class ManagedInferenceEmbeddingModel implements EmbeddingModel {
    private final LocalInferenceGateway gateway;
    private final UUID profileId;
    private final int dimensions;
    private final Duration timeout;

    public ManagedInferenceEmbeddingModel(
            LocalInferenceGateway gateway, UUID profileId, int dimensions, Duration timeout) {
        this.gateway = java.util.Objects.requireNonNull(gateway, "gateway");
        this.profileId = java.util.Objects.requireNonNull(profileId, "profileId");
        if (dimensions < 1) throw new IllegalArgumentException("嵌入维度必须大于零");
        this.dimensions = dimensions;
        this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        try {
            var value = gateway.embeddings(new InferenceEmbeddingRequest(
                    UUID.randomUUID().toString(), profileId, request.getInstructions(), timeout));
            if (value.dimensions() != dimensions) {
                throw new IllegalStateException("本地嵌入实际维度与档案不一致: "
                        + value.dimensions() + " != " + dimensions);
            }
            List<Embedding> embeddings = new ArrayList<>(value.embeddings().size());
            for (int index = 0; index < value.embeddings().size(); index++) {
                embeddings.add(new Embedding(value.embeddings().get(index), index));
            }
            var usage = value.usage();
            var metadata = new EmbeddingResponseMetadata("deliverance:" + value.model(),
                    new DefaultUsage(safeInt(usage.promptTokens()), 0, safeInt(usage.totalTokens())));
            return new EmbeddingResponse(embeddings, metadata);
        } catch (LocalInferenceGateway.InferenceException failure) {
            throw new IllegalStateException("本地嵌入失败 [" + failure.code() + "]: " + failure.getMessage(), failure);
        }
    }

    @Override
    public float[] embed(Document document) {
        return embed(getEmbeddingContent(document));
    }

    @Override
    public int dimensions() { return dimensions; }

    private static int safeInt(long value) { return (int) Math.min(Integer.MAX_VALUE, value); }
}
