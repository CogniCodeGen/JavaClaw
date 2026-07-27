package com.javaclaw.memory.embed;

import java.time.Instant;

/** 工作区唯一嵌入网关的只读健康快照。 */
public record EmbeddingHealthSnapshot(
        EmbeddingHealthStatus status,
        String lastError,
        int consecutiveFailures,
        Instant circuitOpenUntil,
        Instant updatedAt
) {
    public boolean usable() {
        return status == EmbeddingHealthStatus.HEALTHY;
    }
}
