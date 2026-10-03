package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.core.StepContextPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskRepairContextTest {
    @Test
    void acceptsOnlyFrameworkFeedbackBoundToTheExactRunAndModelStep() {
        var valid = event("run", "framework.springai", 2, "model");
        assertTrue(TaskRepairContext.isRepair(TaskRepairContext.fromEvent(valid, "run", "model")));
        for (var invalid : List.of(event("other", "framework.springai", 2, "model"),
                event("run", "tool", 2, "model"), event("run", "framework.springai", 4, "model"),
                event("run", "framework.springai", 2, "other"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> TaskRepairContext.fromEvent(invalid, "run", "model"));
        }
        assertFalse(TaskRepairContext.isRepair(new UserMessage("任务验收尚未通过")));
    }

    @Test
    void metadataSurvivesPersistenceAndRemainsRequiredBeforeTheCurrentToolManifest() {
        var feedback = TaskRepairContext.fromEvent(
                event("run", "framework.springai", 1, "model"), "run", "model");
        Message restored = StepMessageCodec.message(StepMessageCodec.message(feedback));
        assertTrue(TaskRepairContext.isRepair(restored));
        var latestManifest = ProviderToolManifest.message(List.of());
        List<Message> projected = new StepContextProjector(
                new StepContextPolicy(4_000, 48_000, 1, 1_000, 64),
                new com.fasterxml.jackson.databind.ObjectMapper()).project(List.of(
                        new UserMessage("original"), restored,
                        AssistantMessage.builder().content("old answer".repeat(600)).build(),
                        latestManifest)).messages();
        assertEquals(restored, TaskRepairContext.latest(projected));
        assertEquals(latestManifest, projected.getLast());
        assertEquals(feedback.getText(), TaskRepairContext.plannerFeedback(projected, 1_000));
    }

    static RunEventEnvelope event(String run, String producer, int version, String model) {
        return new RunEventEnvelope(run, 5, Instant.EPOCH, "core.task.repair_requested", version,
                producer, null, null, JsonNodeFactory.instance.objectNode().put("modelStepId", model)
                        .put("feedback", "任务验收尚未通过。缺少证据的条件：显示文档预览页"));
    }
}
