package com.javaclaw.inference.api;

import java.time.Duration;
import java.util.List;

/** 批量嵌入结果，向量顺序与请求输入严格一致。 */
public record InferenceEmbeddingResponse(
        String requestId,
        String model,
        int dimensions,
        List<float[]> embeddings,
        InferenceUsage usage,
        Duration inferenceTime) {

    public InferenceEmbeddingResponse {
        requestId = requestId == null ? "" : requestId;
        model = model == null ? "" : model;
        if (dimensions <= 0) throw new IllegalArgumentException("嵌入维度必须大于零");
        embeddings = embeddings == null ? List.of() : List.copyOf(embeddings);
        for (float[] vector : embeddings) {
            if (vector == null || vector.length != dimensions) {
                throw new IllegalArgumentException("嵌入向量维度不一致");
            }
        }
        usage = usage == null ? new InferenceUsage(0, 0) : usage;
        inferenceTime = inferenceTime == null ? Duration.ZERO : inferenceTime;
    }
}
