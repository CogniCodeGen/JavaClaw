package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.Optional;

/** Host-only read of an already selected live page. It must never dispatch navigation. */
public interface BrowserNavigationNoOpProvider {
    Optional<Prepared> prepareNavigationNoOp(JsonNode arguments, ToolExecutionContext context,
            JsonNode observedIdentity);

    @FunctionalInterface
    interface Prepared {
        Completion execute(ToolExecutionContext context, Clock clock);
    }

    record Completion(ToolExecutionResultV1 result, EffectReceiptV1 receipt) {
        public Completion {
            java.util.Objects.requireNonNull(result, "result");
            java.util.Objects.requireNonNull(receipt, "receipt");
        }
    }
}
