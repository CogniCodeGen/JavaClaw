package com.javaclaw.framework.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** One durably identified provider response and all data needed by product-side projections. */
public record ModelUsageFact(
        RunId runId,
        RunScope scope,
        String modelCallId,
        Instant occurredAt,
        ModelTokenUsage usage,
        long pricingInputTokens,
        BigDecimal estimatedCostCny) {

    public ModelUsageFact {
        runId = Objects.requireNonNull(runId, "runId");
        scope = Objects.requireNonNull(scope, "scope");
        modelCallId = Objects.requireNonNull(modelCallId, "modelCallId").strip();
        if (modelCallId.isEmpty()) throw new IllegalArgumentException("modelCallId is blank");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        usage = usage == null ? ModelTokenUsage.ZERO : usage;
        pricingInputTokens = Math.max(0, pricingInputTokens);
        estimatedCostCny = estimatedCostCny == null
                ? BigDecimal.ZERO : estimatedCostCny.max(BigDecimal.ZERO);
    }
}
