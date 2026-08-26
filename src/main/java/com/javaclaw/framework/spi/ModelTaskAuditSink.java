package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.ModelTokenUsage;

public interface ModelTaskAuditSink {
    void started(ModelTaskRequest request);

    /** Records every provider response, including attempts later rejected by parsing/schema checks. */
    default void usage(
            ModelTaskRequest request,
            String model,
            int attempt,
            long inputTokens,
            long outputTokens,
            java.math.BigDecimal estimatedCostCny) { }

    default void usage(ModelTaskRequest request, String model, int attempt,
                       ModelTokenUsage usage, java.math.BigDecimal estimatedCostCny) {
        usage(request, model, attempt, usage.inputTokens(), usage.outputTokens(), estimatedCostCny);
    }

    /** Commits one provider response usage fact. False means no durable owner accepted it. */
    default boolean commitUsage(
            ModelTaskRequest request,
            String modelCallId,
            String model,
            int attempt,
            ModelTokenUsage usage,
            java.math.BigDecimal estimatedCostCny) {
        usage(request, model, attempt, usage, estimatedCostCny);
        return true;
    }

    /** Commits the full accounting fact while preserving the legacy SPI above. */
    default boolean commitUsage(
            ModelTaskRequest request,
            String modelCallId,
            String model,
            int attempt,
            java.time.Instant occurredAt,
            ModelTokenUsage usage,
            long pricingInputTokens,
            java.math.BigDecimal estimatedCostCny) {
        return commitUsage(
                request, modelCallId, model, attempt, usage, estimatedCostCny);
    }

    void completed(ModelTaskRequest request, ModelTaskResult result);

    void failed(ModelTaskRequest request, Throwable failure);
}
