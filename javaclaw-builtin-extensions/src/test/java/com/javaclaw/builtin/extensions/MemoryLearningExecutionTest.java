package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ConversationEvidencePort;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobStepResult;
import com.javaclaw.extension.spi.ExtensionJobUnit;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.extension.spi.OrchestratedTurnCommand;
import com.javaclaw.extension.spi.OrchestratedTurnResult;
import com.javaclaw.extension.spi.OrchestratedTurnSummary;
import com.javaclaw.extension.spi.ScheduleDefinitionBindingPort;
import com.javaclaw.extension.spi.ScheduleLifecyclePort;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.TurnOrchestrationPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryLearningExecutionTest {
    @Test
    void preparesOneBoundedBatchAndRecoveryDoesNotRepeatTheModelOrPublishTwice() throws Exception {
        var fixture = new Fixture("我使用 Java", false);
        var prepared = fixture.execute("first", fixture.initial());
        assertEquals(ExecutionState.RUNNING, prepared.nextState());
        assertEquals(0, fixture.calls.get());
        var completed = fixture.execute("first", prepared.checkpoint());
        assertEquals(ExecutionState.COMPLETED, completed.nextState());
        assertEquals(1, fixture.calls.get());
        fixture.execute("first", prepared.checkpoint());
        assertEquals(1, fixture.calls.get());
        var proposals = fixture.support.store.inTransaction(
                MemoryStoreAccess.ID,
                transaction -> transaction.list(MemoryCollectionNames.proposals(fixture.support.workspaceId), "", 100));
        assertEquals(1, proposals.size());
        var proposal = fixture.support.payloads.decode(proposals.getFirst().payload(), MemoryContracts.Proposal.class);
        assertEquals(MemoryContracts.ProposalState.PENDING, proposal.state());
        assertTrue(proposal.concerns().contains(MemoryContracts.ProposalConcern.UNCERTAIN_EVIDENCE));
    }

    @Test
    void unknownOutcomeBlocksAutomaticReattemptAndSubsequentBatchWithoutAdvancingCursor() throws Exception {
        var fixture = new Fixture("项目说明", true);
        var prepared = fixture.execute("first", fixture.initial());
        var unknown = fixture.execute("first", prepared.checkpoint());
        assertEquals(ExecutionState.WAITING_INPUT, unknown.nextState());
        fixture.execute("first", prepared.checkpoint());
        assertEquals(1, fixture.calls.get());
        var nextJob = fixture.execute("next", fixture.initial());
        assertEquals(ExecutionState.COMPLETED, nextJob.nextState());
        assertEquals(1, fixture.calls.get());
        var cursor = fixture.support.store.inTransaction(
                MemoryStoreAccess.ID,
                transaction -> fixture.support.payloads.decode(
                        transaction
                                .get(MemoryLearningState.progress(fixture.support.workspaceId), "cursor")
                                .orElseThrow()
                                .payload(),
                        MemoryLearningState.Progress.class));
        assertEquals(new ConversationEvidencePort.Cursor(0, 0), cursor.cursor());
        assertTrue(cursor.pendingBatch().isPresent());
    }

    @Test
    void oversizedSingleItemIsAuditedAndCursorAdvancesWithoutModelTurn() throws Exception {
        var fixture = new Fixture("文".repeat(5000), false);
        var prepared = fixture.execute("large", fixture.initial());
        var completed = fixture.execute("large", prepared.checkpoint());
        assertEquals(ExecutionState.COMPLETED, completed.nextState());
        assertEquals(0, fixture.calls.get());
        var batch = fixture.support.store.inTransaction(
                MemoryStoreAccess.ID,
                transaction -> fixture.support.payloads.decode(
                        transaction
                                .get(MemoryLearningState.batches(fixture.support.workspaceId), "batch-large")
                                .orElseThrow()
                                .payload(),
                        MemoryLearningState.Batch.class));
        assertEquals(1, batch.deferred().size());
        assertEquals(new ConversationEvidencePort.Cursor(1, 1), batch.next());
    }

    @Test
    void policyDisabledAfterPreparationPreventsModelExecutionAndLeavesAuditableBatch() throws Exception {
        var fixture = new Fixture("我使用 Java", false);
        var prepared = fixture.execute("first", fixture.initial());
        fixture.support.store.inTransaction(MemoryStoreAccess.ID, transaction -> {
            transaction.put(
                    MemoryCollectionNames.settings(fixture.support.workspaceId),
                    "learning",
                    0,
                    fixture.support.payloads.encode(new MemoryContracts.LearningSettings(
                            1, MemoryContracts.LearningPolicy.OFF, BuiltinExtensionTestSupport.NOW)));
            return null;
        });
        assertEquals(
                ExecutionState.WAITING_INPUT,
                fixture.execute("first", prepared.checkpoint()).nextState());
        assertEquals(0, fixture.calls.get());
    }

    @Test
    void exactLowRiskUserFactRequiresBothFrozenAndCurrentAutomaticPolicy() throws Exception {
        var automatic = new Fixture("我使用 Java", false);
        automatic.summary = false;
        automatic.policy(MemoryContracts.LearningPolicy.AUTO_LOW_RISK);
        var prepared = automatic.execute("auto", automatic.initial());
        automatic.execute("auto", prepared.checkpoint());
        assertEquals(
                MemoryContracts.ProposalState.AUTO_ACCEPTED,
                automatic.proposals().getFirst().state());
        var upgraded = new Fixture("我使用 Java", false);
        upgraded.summary = false;
        upgraded.currentPolicy(MemoryContracts.LearningPolicy.AUTO_LOW_RISK);
        prepared = upgraded.execute("upgraded", upgraded.initial());
        upgraded.execute("upgraded", prepared.checkpoint());
        assertEquals(
                MemoryContracts.ProposalState.PENDING,
                upgraded.proposals().getFirst().state());
    }

    @Test
    void publicationRechecksPolicyChangedDuringTheModelTurn() throws Exception {
        for (var policy : List.of(MemoryContracts.LearningPolicy.SUGGEST, MemoryContracts.LearningPolicy.OFF)) {
            var fixture = new Fixture("我使用 Java", false);
            fixture.summary = false;
            fixture.policy(MemoryContracts.LearningPolicy.AUTO_LOW_RISK);
            fixture.onTurn = () -> fixture.currentPolicy(policy);
            var prepared = fixture.execute("changed", fixture.initial());
            var result = fixture.execute("changed", prepared.checkpoint());
            if (policy == MemoryContracts.LearningPolicy.OFF) {
                assertEquals(ExecutionState.WAITING_INPUT, result.nextState());
                assertTrue(fixture.proposals().isEmpty());
            } else {
                assertEquals(ExecutionState.COMPLETED, result.nextState());
                assertEquals(
                        MemoryContracts.ProposalState.PENDING,
                        fixture.proposals().getFirst().state());
            }
        }
    }

    @Test
    void sensitiveAndSpeculativeVerbatimAlwaysRequireReviewAndIncompleteTurnNeverPublishes() throws Exception {
        for (String text : List.of("密码是测试文本", "也许我会用 Java")) {
            var fixture = new Fixture(text, false);
            fixture.summary = false;
            fixture.policy(MemoryContracts.LearningPolicy.AUTO_LOW_RISK);
            var prepared = fixture.execute("review", fixture.initial());
            fixture.execute("review", prepared.checkpoint());
            assertEquals(
                    MemoryContracts.ProposalState.PENDING,
                    fixture.proposals().getFirst().state());
            assertTrue(!fixture.proposals().getFirst().concerns().isEmpty());
        }
        var failed = new Fixture("原文", false);
        failed.resultStatus = TurnStatus.FAILED;
        var prepared = failed.execute("failed", failed.initial());
        assertEquals(
                ExecutionState.WAITING_INPUT,
                failed.execute("failed", prepared.checkpoint()).nextState());
        assertTrue(failed.proposals().isEmpty());
    }

    @Test
    void fullSerializedEvidenceBudgetIncludesMetadataAndLeavesUnprocessedCursorForNextPeriod() throws Exception {
        var fixture = new Fixture("a", false);
        fixture.evidenceCount = 200;
        fixture.execute("many", fixture.initial());
        var batch = fixture.support.store.inTransaction(
                MemoryStoreAccess.ID,
                transaction -> fixture.support.payloads.decode(
                        transaction
                                .get(MemoryLearningState.batches(fixture.support.workspaceId), "batch-many")
                                .orElseThrow()
                                .payload(),
                        MemoryLearningState.Batch.class));
        assertTrue(batch.evidence().size() < 200);
        assertEquals(batch.evidence().size(), batch.next().itemSequence());
        assertTrue(fixture.support
                        .payloads
                        .encode(Map.of("evidence", batch.evidence()))
                        .json()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        .length
                <= 12000);
    }

    @Test
    void manualWriteDuringModelExecutionIsReclassifiedUnderTheLatestHeadEvenWithDifferentTags() throws Exception {
        var fixture = new Fixture("我使用 Java", false);
        fixture.summary = false;
        fixture.policy(MemoryContracts.LearningPolicy.AUTO_LOW_RISK);
        fixture.onTurn = () -> {
            try {
                var memory = fixture.support.start(new MemoryExtension());
                memory.command(fixture.support.request(
                        "create",
                        new MemoryContracts.CreateRequest(
                                "manual",
                                MemoryContracts.MemoryKind.FACT,
                                "workspace",
                                "我使用 Kotlin",
                                java.util.Set.of("language"),
                                true,
                                Optional.empty()),
                        Optional.of("manual-write"),
                        0));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
        var prepared = fixture.execute("concurrent", fixture.initial());
        fixture.execute("concurrent", prepared.checkpoint());
        assertEquals(
                MemoryContracts.ProposalState.PENDING,
                fixture.proposals().getFirst().state());
        assertTrue(
                fixture.proposals().getFirst().concerns().contains(MemoryContracts.ProposalConcern.UNCERTAIN_EVIDENCE));
    }

    private static final class Fixture {
        private final BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        private final AtomicInteger calls = new AtomicInteger();
        private final MemoryLearningJobExecutor executor;
        private CanonicalPayload frozen;
        private boolean summary = true;
        private int evidenceCount = 1;
        private TurnStatus resultStatus = TurnStatus.COMPLETED;
        private Runnable onTurn = () -> {};

        private Fixture(String text, boolean fail) throws Exception {
            var base = support.executionSnapshot(new AgentRoleRef("profile", 1));
            var platform = AutomationV6Fixtures.snapshot(
                    base.role(),
                    base.provider(),
                    base.permissionProfile(),
                    MemoryLearningResource.restricted(ExecutionOverrides.empty())
                            .budget()
                            .orElseThrow(),
                    base.toolCatalog(),
                    Optional.empty());
            var definition = new MemoryV3Contracts.LearningDefinition(
                    MemoryLearningState.DEFINITION_ID,
                    1,
                    "学习",
                    true,
                    ExecutionOverrides.empty(),
                    BuiltinExtensionTestSupport.NOW.minus(Duration.ofDays(30)),
                    BuiltinExtensionTestSupport.NOW);
            frozen = support.payloads.encode(
                    new MemoryLearningState.Frozen(definition, platform, MemoryContracts.LearningPolicy.SUGGEST));
            support.store.inTransaction(MemoryStoreAccess.ID, transaction -> {
                transaction.put(
                        MemoryLearningState.definitions(support.workspaceId),
                        definition.id(),
                        0,
                        support.payloads.encode(definition));
                return null;
            });
            var runtime = new ExtensionJobRuntimeContext(
                    support.clock,
                    support.payloads,
                    turns(text, fail),
                    support.store,
                    invocation -> {
                        throw new IllegalStateException("no services");
                    },
                    support.embeddings,
                    AutomationStepPort.unavailable(),
                    ScheduledCommandPort.unavailable(),
                    ScheduleLifecyclePort.unavailable(),
                    evidence(text),
                    ScheduleDefinitionBindingPort.unavailable());
            executor = new MemoryLearningJobExecutor(runtime);
        }

        private TurnOrchestrationPort turns(String text, boolean fail) {
            return new TurnOrchestrationPort() {
                @Override
                public OrchestratedTurnResult execute(OrchestratedTurnCommand command, CancellationToken cancellation) {
                    throw new AssertionError("learning must use derived provenance");
                }

                @Override
                public OrchestratedTurnResult executeDerived(
                        OrchestratedTurnCommand command, CancellationToken cancellation) {
                    calls.incrementAndGet();
                    onTurn.run();
                    assertTrue(command.executionSnapshot().toolCatalog().tools().isEmpty());
                    assertEquals(0, command.executionSnapshot().turnBudget().toolCalls());
                    if (fail) {
                        throw new IllegalStateException("unknown provider outcome");
                    }
                    String output = support.payloads
                            .encode(new MemoryLearningState.ModelOutput(
                                    List.of(new MemoryLearningState.Candidate(0, text, text, "workspace", summary))))
                            .json();
                    return new OrchestratedTurnResult(
                            ThreadId.random(),
                            TurnId.random(),
                            resultStatus,
                            support.payloads.encode(
                                    new OrchestratedTurnSummary(output, 100, 100, 0, Optional.empty(), List.of())));
                }
            };
        }

        private ConversationEvidencePort evidence(String text) {
            return new ConversationEvidencePort() {
                @Override
                public long committedUpperBound(com.javaclaw.api.WorkspaceId workspaceId) {
                    return 1;
                }

                @Override
                public Page scan(
                        com.javaclaw.api.WorkspaceId workspaceId,
                        Cursor after,
                        long upperSequence,
                        java.time.Instant completedAfter,
                        int limit) {
                    assertEquals(200, limit);
                    var values = java.util.stream.IntStream.rangeClosed(1, evidenceCount)
                            .mapToObj(index -> new Evidence(
                                    new Cursor(1, index),
                                    ThreadId.random(),
                                    TurnId.random(),
                                    ItemId.random(),
                                    SourceKind.USER_TEXT,
                                    text,
                                    "a".repeat(64),
                                    false))
                            .toList();
                    return new Page(values, new Cursor(1, evidenceCount), false);
                }
            };
        }

        private void policy(MemoryContracts.LearningPolicy policy) {
            var previous = support.payloads.decode(frozen, MemoryLearningState.Frozen.class);
            frozen = support.payloads.encode(
                    new MemoryLearningState.Frozen(previous.definition(), previous.platform(), policy));
            currentPolicy(policy);
        }

        private void currentPolicy(MemoryContracts.LearningPolicy policy) {
            try {
                support.store.inTransaction(MemoryStoreAccess.ID, transaction -> {
                    var current = transaction.get(MemoryCollectionNames.settings(support.workspaceId), "learning");
                    long revision = current.map(com.javaclaw.extension.spi.VersionedDocument::revision)
                            .orElse(0L);
                    transaction.put(
                            MemoryCollectionNames.settings(support.workspaceId),
                            "learning",
                            revision,
                            support.payloads.encode(new MemoryContracts.LearningSettings(
                                    revision + 1, policy, BuiltinExtensionTestSupport.NOW)));
                    return null;
                });
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        private List<MemoryContracts.Proposal> proposals() throws Exception {
            return support.store.inTransaction(
                    MemoryStoreAccess.ID,
                    transaction -> new MemoryStoreAccess(support.payloads)
                            .allProposals(transaction, MemoryCollectionNames.proposals(support.workspaceId)));
        }

        private CanonicalPayload initial() {
            return support.payloads.encode(new MemoryLearningState.Checkpoint("prepare", ""));
        }

        private ExtensionJobStepResult execute(String id, CanonicalPayload checkpoint) throws Exception {
            var job = new ExtensionJob(
                    id,
                    MemoryStoreAccess.ID,
                    support.workspaceId,
                    MemoryLearningState.JOB_TYPE,
                    MemoryLearningState.DEFINITION_ID,
                    1,
                    frozen,
                    ExecutionState.RUNNING,
                    1,
                    checkpoint,
                    2,
                    Optional.of(1L),
                    Optional.empty(),
                    BuiltinExtensionTestSupport.NOW,
                    BuiltinExtensionTestSupport.NOW);
            var unit = new ExtensionJobUnit(
                    id,
                    1,
                    "unit",
                    support.payloads.encode(Map.of()),
                    ExtensionJobUnitState.INTENT_RECORDED,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    BuiltinExtensionTestSupport.NOW,
                    Optional.empty());
            return executor.execute(new ExtensionJobExecution(job, unit), support.cancellation);
        }
    }
}
