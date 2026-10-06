package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.spi.StoredRun;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/** 按冻结预算与可信生命周期事件还原 Turn 的执行截止时间。 */
final class PersistedRunDeadline {
    private PersistedRunDeadline() { }

    static Instant resolve(StoredRun stored, RunBudget budget, List<RunEventEnvelope> events) {
        return resolve(stored, budget, events, events.isEmpty()
                ? stored.snapshot().createdAt() : events.getLast().timestamp());
    }

    static Instant resolve(StoredRun stored, RunBudget budget, List<RunEventEnvelope> events, Instant now) {
        Instant deadline = original(stored, budget, events);
        if (!workflowCoordinator(stored.request())) return deadline;
        Duration waiting = Duration.ZERO;
        Instant pausedAt = null;
        for (RunEventEnvelope event : events) {
            if (event.schemaVersion() != 1 || !event.producer().equals("framework.core")) continue;
            if (event.type().equals("core.run.waiting_input") || event.type().equals("core.run.paused")
                    || event.type().equals("core.run.recovered_paused")) {
                if (pausedAt == null) pausedAt = event.timestamp();
            } else if (event.type().equals("core.run.resumed") || event.terminal()) {
                if (pausedAt != null && event.timestamp().isAfter(pausedAt))
                    waiting = waiting.plus(Duration.between(pausedAt, event.timestamp()));
                pausedAt = null;
            }
        }
        if (pausedAt != null && !stored.snapshot().state().terminal() && now.isAfter(pausedAt))
            waiting = waiting.plus(Duration.between(pausedAt, now));
        return deadline.plus(waiting);
    }

    static boolean workflowCoordinator(RunRequest request) {
        return request.source().kind().equals("workflow")
                && request.attributes().getOrDefault("framework.managed",
                        com.fasterxml.jackson.databind.node.BooleanNode.FALSE).asBoolean()
                && request.source().id().equals(request.attributes().getOrDefault("framework.managedTaskId",
                        com.fasterxml.jackson.databind.node.TextNode.valueOf("")).asText());
    }

    private static Instant original(StoredRun stored, RunBudget budget, List<RunEventEnvelope> events) {
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
