package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.ModelTokenUsage;
import com.javaclaw.framework.api.ModelUsageFact;

import java.math.BigDecimal;

/** Optional product projection of authoritative per-run model usage. */
@FunctionalInterface
public interface RunUsageObserver {
    void recorded(RunId runId, RunScope scope, long inputTokens,
                  long outputTokens, BigDecimal cost);

    default void recorded(RunId runId, RunScope scope, ModelTokenUsage usage,
                          BigDecimal cost) {
        recorded(runId, scope, usage.inputTokens(), usage.outputTokens(), cost);
    }

    /** Rich, durably identified usage path used by reliable product projections. */
    default void recorded(ModelUsageFact fact) {
        recorded(fact.runId(), fact.scope(), fact.usage(), fact.estimatedCostCny());
    }

    static RunUsageObserver noop() {
        return (runId, scope, inputTokens, outputTokens, cost) -> { };
    }
}
