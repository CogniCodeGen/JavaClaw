package com.javaclaw.server.extension;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.ScheduleContracts;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.protocol.InputJobRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRecoveryIntegrationTest {
    private static final String MEMORY = BuiltinExtensionIds.MEMORY;
    private static final Clock CLOCK = Clock.fixed(Instant.now(), ZoneOffset.UTC);

    @TempDir
    Path temporary;

    @Test
    void cancellationReachesTheLearningTurnAndLeavesAnExplicitlyRecoverableBatch() throws Exception {
        var model = new MemoryIntegrationModel();
        try (var fixture = new MemoryIntegrationFixture(temporary.resolve("data-v6"), CLOCK, model)) {
            fixture.installProvider();
            var workspace = fixture.workspace("cancel");
            fixture.conversation(workspace, "记忆事实：取消后不可发布");
            fixture.save(workspace, true, 0);
            var binding = fixture.awaitBinding(workspace);
            model.blockLearning();
            var occurrence = fixture.fire(workspace, binding.scheduleId(), 1);
            fixture.awaitLearning(workspace);
            var job = learningJob(fixture, workspace);
            fixture.write(
                    "extension/job/cancel", new InputJobRpcContracts.JobMutationPayload(job.id()), job.revision());
            fixture.awaitJob(job.id(), ExecutionState.CANCELLED);
            assertEquals(
                    TurnStatus.CANCELLED,
                    fixture.turn(model.learningTurns.getFirst()).status());
            assertTrue(fixture.proposals(workspace).isEmpty());
            assertEquals("UNKNOWN", field(fixture, fixture.batches(workspace).getFirst(), "state"));
            MemoryIntegrationFixture.await(
                    () -> fixture.occurrences(workspace).stream()
                            .anyMatch(value -> value.identity().equals(occurrence.identity())
                                    && value.status().state() == ScheduleContracts.OccurrenceState.CANCELLED),
                    "学习取消后 Occurrence 未收敛为 CANCELLED");
            assertEquals(1, model.learningInvocations.size());
        }
    }

    @Test
    void unknownBatchSurvivesServerRestartAndExplicitRetryKeepsItsOriginalEvidence() throws Exception {
        Path dataRoot = temporary.resolve("data-v6");
        Workspace workspace;
        String originalJob;
        var firstModel = new MemoryIntegrationModel();
        firstModel.invalidOutput = true;
        try (var fixture = new MemoryIntegrationFixture(dataRoot, CLOCK, firstModel)) {
            fixture.installProvider();
            workspace = fixture.workspace("restore");
            fixture.conversation(workspace, "记忆事实：原冻结证据");
            fixture.save(workspace, true, 0);
            fixture.awaitBinding(workspace);
            originalJob = fixture.run(workspace, 1).id();
            fixture.awaitJob(originalJob, ExecutionState.WAITING_INPUT);
            assertEquals(1, firstModel.learningInvocations.size());
        }
        var restoredModel = new MemoryIntegrationModel();
        try (var fixture = new MemoryIntegrationFixture(dataRoot, CLOCK, restoredModel)) {
            fixture.awaitBinding(workspace);
            assertEquals(
                    ExecutionState.WAITING_INPUT, fixture.job(originalJob).job().state());
            assertEquals("UNKNOWN", field(fixture, fixture.batches(workspace).getFirst(), "state"));
            fixture.conversation(workspace, "记忆事实：恢复后新增证据");
            var blocked = fixture.run(workspace, 1);
            fixture.awaitJob(blocked.id(), ExecutionState.COMPLETED);
            assertTrue(restoredModel.learningInvocations.isEmpty());
            var other = fixture.workspace("isolated");
            var frozen = fixture.batches(workspace).getFirst();
            assertTrue(fixture.rejectedCommand(
                            other,
                            MEMORY,
                            "learning/batch/skip",
                            new MemoryContracts.Key(field(fixture, frozen, "id")),
                            revision(fixture, frozen))
                    .error()
                    .isPresent());
            retry(fixture, workspace, frozen);
            assertEquals(List.of("记忆事实：原冻结证据"), contents(fixture, workspace));
            assertEquals(
                    ExecutionState.CANCELLED, fixture.job(originalJob).job().state());
            var incremental = fixture.run(workspace, 1);
            fixture.awaitJob(incremental.id(), ExecutionState.COMPLETED);
            assertEquals(Set.of("记忆事实：原冻结证据", "记忆事实：恢复后新增证据"), Set.copyOf(contents(fixture, workspace)));
            assertEquals(2, restoredModel.learningInvocations.size());
            var empty = fixture.run(workspace, 1);
            fixture.awaitJob(empty.id(), ExecutionState.COMPLETED);
            assertEquals(2, restoredModel.learningInvocations.size());
            assertFalse(fixture.batches(workspace).isEmpty());
        }
    }

    private static void retry(MemoryIntegrationFixture fixture, Workspace workspace, CanonicalPayload frozen)
            throws Exception {
        fixture.command(
                workspace,
                MEMORY,
                "learning/batch/retry",
                new MemoryContracts.Key(field(fixture, frozen, "id")),
                revision(fixture, frozen),
                Object.class);
        String owner = field(fixture, fixture.batches(workspace).getFirst(), "ownerJobId");
        fixture.awaitJob(owner, ExecutionState.COMPLETED);
        assertEquals("COMMITTED", field(fixture, fixture.batches(workspace).getFirst(), "state"));
    }

    private static List<String> contents(MemoryIntegrationFixture fixture, Workspace workspace) {
        return fixture.proposals(workspace).stream()
                .map(value -> value.candidate().content())
                .toList();
    }

    private static ExtensionExecutionReceipt learningJob(MemoryIntegrationFixture fixture, Workspace workspace) {
        return fixture.jobs(workspace).stream()
                .filter(value -> value.jobType().equals("conversation-learning"))
                .findFirst()
                .orElseThrow();
    }

    private static String field(MemoryIntegrationFixture fixture, CanonicalPayload payload, String field) {
        return fixture.components.json().textField(payload, field).orElseThrow();
    }

    private static long revision(MemoryIntegrationFixture fixture, CanonicalPayload payload) {
        return fixture.components.json().integerField(payload, "revision").orElseThrow();
    }
}
