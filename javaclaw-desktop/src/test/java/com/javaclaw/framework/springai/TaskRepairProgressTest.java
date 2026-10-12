package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.TaskOutcome;
import com.javaclaw.framework.api.TaskResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRepairProgressTest {
    @Test
    void renamedSubjectAndDisplayCriteriaCannotInventProgressForTheSameFrame() {
        RunEventEnvelope repair = event(5, "core.task.repair_requested",
                JsonNodeFactory.instance.objectNode(), "framework.springai");
        RunEventEnvelope before = observation(3, "原标题", "17");
        RunEventEnvelope repeated = observation(6, "新的标题", "17");
        TaskResult renamed = new TaskResult(TaskOutcome.PARTIAL, List.of("另一条件"), "",
                List.of("receipt:6"), List.of("改名后的描述"));

        assertFalse(TaskRepairProgress.meaningfulRepairProgress(
                List.of(repair), List.of(before, repeated), repair, renamed));
        assertTrue(TaskRepairProgress.meaningfulRepairProgress(
                List.of(repair), List.of(before, observation(6, "新的标题", "18")),
                repair, renamed));
    }

    @Test
    void subjectAloneIsNotAnObservationRevision() {
        RunEventEnvelope repair = event(5, "core.task.repair_requested",
                JsonNodeFactory.instance.objectNode(), "framework.springai");
        assertFalse(TaskRepairProgress.meaningfulRepairProgress(List.of(repair),
                List.of(observation(6, "模型说已换页", "")), repair,
                TaskResult.unverified("missing frame proof")));
    }

    private static RunEventEnvelope observation(long sequence, String subject, String revision) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("tool", "desktop_session_observe")
                .put("operation", "observe")
                .put("target", "示例应用")
                .put("status", "OBSERVED")
                .put("subject", subject);
        ObjectNode metadata = payload.putObject("metadata")
                .put("sessionId", "session-1")
                .put("targetId", "window-1");
        if (!revision.isBlank()) metadata.put("contentRevision", revision);
        return event(sequence, "core.tool.receipt", payload, "framework.core");
    }

    private static RunEventEnvelope event(long sequence, String type, ObjectNode payload,
                                          String producer) {
        return new RunEventEnvelope("run-1", sequence, Instant.ofEpochSecond(sequence), type,
                1, producer, null, null, payload);
    }
}
