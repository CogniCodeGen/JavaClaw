package com.javaclaw.framework.spi;

import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunScope;

import java.math.BigDecimal;

/** Optional product projection of authoritative per-run model usage. */
@FunctionalInterface
public interface RunUsageObserver {
    void recorded(RunId runId, RunScope scope, long inputTokens,
                  long outputTokens, BigDecimal cost);

    static RunUsageObserver noop() {
        return (runId, scope, inputTokens, outputTokens, cost) -> { };
    }
}
