package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.extension.spi.ConversationEvidencePort;
import com.javaclaw.extension.spi.ExtensionJob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryBatchRecoveryTest {
    @Test
    void successfulRetryReceiptReplaysWhileItsReplacementUnitIsAlreadyRunning() throws Exception {
        var fixture = new Fixture(MemoryLearningState.BatchState.UNKNOWN, ExecutionState.WAITING_INPUT, false);
        var request = fixture.support.request(
                "learning/batch/retry", new MemoryContracts.Key("batch"), Optional.of("retry"), 1);
        var accepted = fixture.memory.command(request);
        var batch = fixture.batch();
        var replacement = fixture.support.jobs.find(batch.ownerJobId()).orElseThrow();
        fixture.support.jobs.record(new ExtensionJob(
                replacement.id(),
                replacement.extensionId(),
                replacement.workspaceId(),
                replacement.jobType(),
                replacement.definitionId(),
                replacement.definitionRevision(),
                replacement.frozenInput(),
                ExecutionState.RUNNING,
                replacement.revision() + 1,
                replacement.checkpoint(),
                replacement.nextUnitSequence() + 1,
                Optional.of(replacement.nextUnitSequence()),
                Optional.empty(),
                replacement.createdAt(),
                replacement.updatedAt()));
        assertEquals(accepted, fixture.memory.command(request));
        assertEquals(batch, fixture.batch());
        assertEquals(
                2,
                fixture.support
                        .jobs
                        .list(Optional.empty(), Optional.empty(), java.util.Set.of(), 100)
                        .size());
        // 同一已提交键改绑内容仍必须失败，不能因跳过安全边界检查而吞掉幂等冲突。
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.memory.command(fixture.support.request(
                        "learning/batch/skip", new MemoryContracts.Key("batch"), Optional.of("retry"), 1)));
    }

    @Test
    void explicitRetryCancelsWaitingOwnerAndSubmitsExactlyOneJobWithFrozenEvidenceAndPolicy() throws Exception {
        var fixture = new Fixture(MemoryLearningState.BatchState.UNKNOWN, ExecutionState.WAITING_INPUT, false);
        var request = fixture.support.request(
                "learning/batch/retry", new MemoryContracts.Key("batch"), Optional.of("retry"), 1);
        fixture.memory.command(request);
        fixture.memory.command(request);
        fixture.memory.bundle().restore(fixture.memory.context());
        var jobs = fixture.support.jobs.list(Optional.empty(), Optional.empty(), java.util.Set.of(), 100);
        assertEquals(2, jobs.size());
        assertEquals(
                ExecutionState.CANCELLED,
                fixture.support.jobs.find("owner").orElseThrow().state());
        var current = fixture.batch();
        assertEquals(2, current.attempt());
        assertEquals(MemoryLearningState.BatchState.FROZEN, current.state());
        assertFalse(current.ownerJobId().equals("owner"));
        var replacement = fixture.support.jobs.find(current.ownerJobId()).orElseThrow();
        assertEquals(fixture.original.frozenInput(), replacement.frozenInput());
        assertEquals(
                "learn",
                fixture.support
                        .payloads
                        .decode(replacement.checkpoint(), MemoryLearningState.Checkpoint.class)
                        .phase());
        assertTrue(fixture.progress().pendingBatch().isPresent());
        assertEquals(
                new ConversationEvidencePort.Cursor(0, 0), fixture.progress().cursor());
    }

    @Test
    void explicitSkipAdvancesOnlyTheFrozenCursorAndIsIdempotent() throws Exception {
        var fixture = new Fixture(MemoryLearningState.BatchState.UNKNOWN, ExecutionState.WAITING_INPUT, false);
        var request = fixture.support.request(
                "learning/batch/skip", new MemoryContracts.Key("batch"), Optional.of("skip"), 1);
        fixture.memory.command(request);
        fixture.memory.command(request);
        assertEquals(MemoryLearningState.BatchState.SKIPPED, fixture.batch().state());
        assertEquals(
                new ConversationEvidencePort.Cursor(10, 7), fixture.progress().cursor());
        assertTrue(fixture.progress().pendingBatch().isEmpty());
        assertEquals(
                1,
                fixture.support
                        .jobs
                        .list(Optional.empty(), Optional.empty(), java.util.Set.of(), 100)
                        .size());
    }

    @Test
    void failedFrozenBatchCanBeRecoveredButActiveOrStaleBatchesCannot() throws Exception {
        var failed = new Fixture(MemoryLearningState.BatchState.FROZEN, ExecutionState.FAILED, false);
        failed.memory.command(failed.support.request(
                "learning/batch/skip", new MemoryContracts.Key("batch"), Optional.of("skip"), 1));
        assertEquals(MemoryLearningState.BatchState.SKIPPED, failed.batch().state());
        for (boolean activeUnit : List.of(false, true)) {
            var active = new Fixture(MemoryLearningState.BatchState.FROZEN, ExecutionState.RUNNING, activeUnit);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> active.memory.command(active.support.request(
                            "learning/batch/retry", new MemoryContracts.Key("batch"), Optional.of("retry"), 1)));
        }
        var stale = new Fixture(MemoryLearningState.BatchState.UNKNOWN, ExecutionState.WAITING_INPUT, false);
        assertThrows(
                IllegalArgumentException.class,
                () -> stale.memory.command(stale.support.request(
                        "learning/batch/retry", new MemoryContracts.Key("batch"), Optional.of("stale"), 2)));
        assertEquals(1, stale.batch().attempt());
    }

    private static final class Fixture {
        private final BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        private final BuiltinExtensionTestSupport.Started memory = support.start(new MemoryExtension());
        private final ExtensionJob original;

        private Fixture(MemoryLearningState.BatchState batchState, ExecutionState state, boolean active)
                throws Exception {
            original = new ExtensionJob(
                    "owner",
                    MemoryStoreAccess.ID,
                    support.workspaceId,
                    MemoryLearningState.JOB_TYPE,
                    MemoryLearningState.DEFINITION_ID,
                    5,
                    support.payloads.encode(Map.of("frozen", "original-policy")),
                    state,
                    1,
                    support.payloads.encode(new MemoryLearningState.Checkpoint("learn", "batch")),
                    2,
                    active ? Optional.of(1L) : Optional.empty(),
                    state == ExecutionState.FAILED ? Optional.of("FAILED") : Optional.empty(),
                    BuiltinExtensionTestSupport.NOW,
                    BuiltinExtensionTestSupport.NOW);
            support.jobs.record(original);
            support.store.inTransaction(MemoryStoreAccess.ID, transaction -> {
                var batch = new MemoryLearningState.Batch(
                        "batch",
                        1,
                        "owner",
                        1,
                        new ConversationEvidencePort.Cursor(0, 0),
                        new ConversationEvidencePort.Cursor(10, 7),
                        10,
                        List.of(),
                        List.of("audited-source"),
                        batchState,
                        "unknown",
                        BuiltinExtensionTestSupport.NOW);
                transaction.put(
                        MemoryLearningState.batches(support.workspaceId), "batch", 0, support.payloads.encode(batch));
                transaction.put(
                        MemoryLearningState.progress(support.workspaceId),
                        "cursor",
                        0,
                        support.payloads.encode(new MemoryLearningState.Progress(
                                1, new ConversationEvidencePort.Cursor(0, 0), Optional.of("batch"))));
                return null;
            });
        }

        private MemoryLearningState.Batch batch() throws Exception {
            return support.store.inTransaction(
                    MemoryStoreAccess.ID,
                    transaction -> support.payloads.decode(
                            transaction
                                    .get(MemoryLearningState.batches(support.workspaceId), "batch")
                                    .orElseThrow()
                                    .payload(),
                            MemoryLearningState.Batch.class));
        }

        private MemoryLearningState.Progress progress() throws Exception {
            return support.store.inTransaction(
                    MemoryStoreAccess.ID,
                    transaction -> support.payloads.decode(
                            transaction
                                    .get(MemoryLearningState.progress(support.workspaceId), "cursor")
                                    .orElseThrow()
                                    .payload(),
                            MemoryLearningState.Progress.class));
        }
    }
}
