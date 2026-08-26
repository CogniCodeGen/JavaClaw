package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunEventEnvelope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurableUsageHistoryTest {
    @Test
    void combinesLegacyBaselinesWithAllFollowingUsageFacts() {
        var primaryLegacy = payload(100, 20, 2, "").put("estimatedCostCny", 1.0);
        var taskLegacy = payload(10, 2, 1, "");
        var primaryV2 = payload(30, 5, 1, "primary-1")
                .put("cacheReadInputTokens", 10).put("reasoningTokens", 2)
                .put("estimatedCostCny", 0.3);
        var taskV2 = payload(7, 1, 1, "task-1").put("estimatedCostCny", 0.1);

        DurableUsageHistory.Snapshot restored = DurableUsageHistory.from(List.of(
                event(1, "core.model.completed", primaryLegacy),
                event(2, "core.model_task.completed", taskLegacy),
                event(3, "core.model.usage", primaryV2),
                event(4, "core.model.completed", payload(130, 25, 3, "")),
                event(5, "core.model_task.usage", taskV2),
                event(6, "core.model_task.completed", payload(7, 1, 1, ""))));

        assertEquals(147, restored.usage().inputTokens());
        assertEquals(28, restored.usage().outputTokens());
        assertEquals(10, restored.usage().cacheReadInputTokens());
        assertEquals(2, restored.usage().reasoningTokens());
        assertEquals(5, restored.usage().modelCalls());
        assertEquals(new BigDecimal("1.4"), restored.cost().stripTrailingZeros());
        assertEquals(java.util.Set.of("primary-1", "task-1"), restored.modelCallIds());
    }

    @Test
    void pureLegacyUsesLastPrimarySnapshotAndAllTaskCompletions() {
        DurableUsageHistory.Snapshot restored = DurableUsageHistory.from(List.of(
                event(1, "core.model.completed", payload(10, 2, 1, "")),
                event(2, "core.model.completed", payload(30, 6, 2, "")),
                event(3, "core.model_task.completed", payload(3, 1, 1, "")),
                event(4, "core.model_task.completed", payload(4, 1, 1, ""))));

        assertEquals(37, restored.usage().inputTokens());
        assertEquals(8, restored.usage().outputTokens());
        assertEquals(4, restored.usage().modelCalls());
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode payload(
            long input, long output, long calls, String callId) {
        var payload = JsonNodeFactory.instance.objectNode();
        payload.put("inputTokens", input);
        payload.put("outputTokens", output);
        payload.put("modelCalls", calls);
        if (!callId.isBlank()) payload.put("modelCallId", callId);
        return payload;
    }

    private static RunEventEnvelope event(
            long sequence, String type, com.fasterxml.jackson.databind.JsonNode payload) {
        return new RunEventEnvelope("run", sequence, Instant.EPOCH.plusSeconds(sequence),
                type, type.endsWith(".usage") ? 2 : 1,
                "test", "correlation", null, payload);
    }
}
