package com.javaclaw.extension.spi;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionJobContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final CanonicalPayload EMPTY = new CanonicalPayload("{}");
    private static final ExtensionId EXTENSION = new ExtensionId("com.javaclaw.workflow");
    private static final WorkspaceId WORKSPACE = new WorkspaceId(new UUID(1, 1));

    @Test
    void submission与mutation冻结定义并校验写身份() {
        ExtensionJobSubmission submission = submission();
        ExtensionJobMutation mutation = new ExtensionJobMutation(" key ", 0);

        assertEquals("workflow", submission.jobType());
        assertEquals("key", mutation.idempotencyKey());
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobMutation("", 0));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionJobMutation("key", -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionJobSubmission(EXTENSION, WORKSPACE, "bad type", "definition", 1, EMPTY, EMPTY));
    }

    @Test
    void job活动单元必须属于Running且位于nextSequence之前() {
        ExtensionJob running = job(ExecutionState.RUNNING, 2, Optional.of(1L), Optional.empty());

        assertEquals(Optional.of(1L), running.activeUnitSequence());
        assertThrows(
                IllegalArgumentException.class, () -> job(ExecutionState.PAUSED, 2, Optional.of(1L), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> job(ExecutionState.RUNNING, 1, Optional.of(1L), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> job(ExecutionState.FAILED, 1, Optional.empty(), Optional.empty()));
    }

    @Test
    void workUnit先记录意图终态再要求结果checkpoint与错误码() {
        ExtensionJobUnit intent = new ExtensionJobUnit(
                "job",
                1,
                "unit",
                EMPTY,
                ExtensionJobUnitState.INTENT_RECORDED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW,
                Optional.empty());
        ExtensionJob running = job(ExecutionState.RUNNING, 2, Optional.of(1L), Optional.empty());

        assertEquals("unit", new ExtensionJobExecution(running, intent).unit().unitId());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionJobUnit(
                        "job",
                        1,
                        "unit",
                        EMPTY,
                        ExtensionJobUnitState.COMPLETED,
                        Optional.of(EMPTY),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        NOW,
                        Optional.of(NOW)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExtensionJobStepResult(
                        EMPTY, EMPTY, ExecutionState.FAILED, Optional.empty(), Optional.empty()));
    }

    private static ExtensionJobSubmission submission() {
        return new ExtensionJobSubmission(EXTENSION, WORKSPACE, "workflow", "definition", 1, EMPTY, EMPTY);
    }

    private static ExtensionJob job(
            ExecutionState state, long nextSequence, Optional<Long> active, Optional<String> errorCode) {
        return new ExtensionJob(
                "job",
                EXTENSION,
                WORKSPACE,
                "workflow",
                "definition",
                1,
                EMPTY,
                state,
                2,
                EMPTY,
                nextSequence,
                active,
                errorCode,
                NOW,
                NOW);
    }
}
