package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.spi.StoredRun;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/** 按创建事件和冻结预算还原 Turn 的原始截止时间。 */
final class PersistedRunDeadline {
    private PersistedRunDeadline() { }

    static Instant resolve(StoredRun stored, RunBudget budget, List<RunEventEnvelope> events) {
        Instant historical = stored.snapshot().createdAt().plus(budget.timeout());
        for (RunEventEnvelope event : events) {
            if (!event.type().equals("core.run.created") || event.schemaVersion() != 1
                    || !event.producer().equals("framework.core")) continue;
            String recorded = event.payload().path("deadline").asText("");
            if (!recorded.isBlank()) {
                try {
                    Instant deadline = Instant.parse(recorded);
                    // 持久化文本不能延长冻结预算允许的截止时间。
                    return deadline.isBefore(historical) ? deadline : historical;
                } catch (DateTimeParseException invalid) {
                    return historical;
                }
            }
            break;
        }
        return historical;
    }
}
