package com.javaclaw.framework.spi;

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

    void completed(ModelTaskRequest request, ModelTaskResult result);

    void failed(ModelTaskRequest request, Throwable failure);
}
