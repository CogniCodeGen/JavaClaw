package com.javaclaw.builtin.extensions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ConversationEvidencePort;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobUnit;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.extension.spi.ScheduleDefinitionBindingPort;
import com.javaclaw.extension.spi.ScheduleLifecyclePort;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryLearningResourceTest {
    @Test
    void ordinaryAndManagedStartsKeepConfiguredReferencesAndNarrowLargerBudgets() throws Exception {
        var fixture = new Fixture();
        var configured = new ExecutionOverrides(
                Optional.of(new AgentRoleRef("profile", 1)),
                Optional.of(new ProviderRef("provider", 1, "model")),
                Optional.of(new PermissionProfileRef("permission", 1)),
                Optional.of(ApprovalPolicy.EVERY_CALL),
                Optional.empty(),
                Optional.empty(),
                Optional.of(ReasoningPreference.HIGH));
        var saved = fixture.saveExecution(configured);
        var generous = new OrchestrationContracts.ExecutionBudget(5, 100000, 5000, 10);
        fixture.start(ExecutionOverrides.empty(), generous, "default-start");
        fixture.start(saved.execution(), MemoryLearningResource.BUDGET, "managed-start");
        var generousTurn = new ExecutionOverrides(
                configured.role(),
                configured.provider(),
                configured.permissionProfile(),
                configured.approvalPolicy(),
                Optional.of(new TurnBudget(100000, 5000, 10, 2, Duration.ofMinutes(5))),
                Optional.empty(),
                configured.reasoning());
        fixture.start(generousTurn, generous, "generous-turn-start");
        assertEquals(
                List.of(saved.execution(), saved.execution(), saved.execution()), fixture.support.frozenSelections);
        assertEquals(4, fixture.jobs().size());
    }

    @Test
    void ordinaryStartRejectsUnmatchedSelectionsBeforeFreezingOrSubmittingAJob() throws Exception {
        var fixture = new Fixture();
        fixture.save(true, 0, "save");
        for (String field : List.of("role", "provider", "permission", "approval", "reasoning")) {
            var rejected = assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.start(changedSelection(field), MemoryLearningResource.BUDGET, field));
            assertTrue(rejected.getMessage().contains("学习执行选择由学习配置管理"));
        }
        assertTrue(fixture.support.frozenSelections.isEmpty());
        assertEquals(1, fixture.jobs().size());
    }

    @Test
    void ordinaryStartRejectsStricterExecutionOrTurnLimitsInsteadOfSilentlyExpandingThem() throws Exception {
        var fixture = new Fixture();
        fixture.save(true, 0, "save");
        for (var budget : List.of(
                new OrchestrationContracts.ExecutionBudget(1, 15999, 2000, 0),
                new OrchestrationContracts.ExecutionBudget(1, 16000, 1999, 0))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.start(ExecutionOverrides.empty(), budget, "total-" + budget));
        }
        for (var budget : List.of(
                new TurnBudget(15999, 2000, 0, 0, Duration.ofSeconds(120)),
                new TurnBudget(16000, 1999, 0, 0, Duration.ofSeconds(120)),
                new TurnBudget(16000, 2000, 0, 0, Duration.ofSeconds(119)))) {
            var execution = new ExecutionOverrides(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(budget),
                    Optional.empty(),
                    Optional.empty());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.start(execution, MemoryLearningResource.BUDGET, "turn-" + budget));
        }
        assertTrue(fixture.support.frozenSelections.isEmpty());
        assertEquals(1, fixture.jobs().size());
    }

    private static ExecutionOverrides changedSelection(String field) {
        return new ExecutionOverrides(
                field.equals("role") ? Optional.of(new AgentRoleRef("other-role", 9)) : Optional.empty(),
                field.equals("provider")
                        ? Optional.of(new ProviderRef("other-provider", 9, "model"))
                        : Optional.empty(),
                field.equals("permission")
                        ? Optional.of(new PermissionProfileRef("other-permission", 9))
                        : Optional.empty(),
                field.equals("approval") ? Optional.of(ApprovalPolicy.EVERY_CALL) : Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                field.equals("reasoning") ? Optional.of(ReasoningPreference.HIGH) : Optional.empty());
    }

    @Test
    void changingEnablementAfterReloadPreservesSavedApprovalAndReasoning() throws Exception {
        var fixture = new Fixture();
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("enabled", true);
        input.put("reschedule", false);
        input.put("role", new com.javaclaw.api.AgentRoleRef("profile", 1));
        input.put("provider", new com.javaclaw.api.ProviderRef("provider", 1, "model"));
        input.put("permissionProfile", new com.javaclaw.api.PermissionProfileRef("permission", 1));
        input.put("approvalPolicy", "EVERY_CALL");
        input.put("reasoning", "HIGH");
        var first = fixture.support.decode(
                fixture.memory.command(
                        fixture.support.request("learning/form/save", input, Optional.of("first-save"), 0)),
                MemoryV3Contracts.LearningDefinition.class);
        var view = fixture.support.decode(
                fixture.memory.query(fixture.support.request(
                        "view.learning.definition",
                        new ViewQueryRequest("learningDefinition", Map.of(), "", 1, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        var reloaded = fixture.support.payloads.decode(view.values(), Map.class);
        input.put("approvalPolicy", reloaded.get("approvalPolicy"));
        input.put("reasoning", reloaded.get("reasoning"));
        input.put("enabled", false);
        var disabled = fixture.support.decode(
                fixture.memory.command(
                        fixture.support.request("learning/form/save", input, Optional.of("disable-saved"), 1)),
                MemoryV3Contracts.LearningDefinition.class);
        assertFalse(disabled.enabled());
        assertEquals(first.execution(), disabled.execution());
    }

    @Test
    void explicitConfigurationPreservesInitialWindowAndImmediatelyRunsThroughTheSameJobReceipt() throws Exception {
        var fixture = new Fixture();
        var support = fixture.support;
        assertTrue(fixture.memory
                .query(support.request("learning/read", Map.of(), Optional.empty(), 0))
                .payload()
                .json()
                .contains("UNBOUND"));
        var saved = fixture.save(true, 0, "save");
        fixture.save(true, 0, "save");
        assertEquals(BuiltinExtensionTestSupport.NOW.minusSeconds(30L * 86400), saved.initialSince());
        assertEquals(1, fixture.jobs().size());
        var request = support.request("learning/run", Map.of(), Optional.of("run"), 1);
        var receipt = support.decode(fixture.memory.command(request), ExtensionExecutionReceipt.class);
        assertEquals(receipt, support.decode(fixture.memory.command(request), ExtensionExecutionReceipt.class));
        var job = support.jobs.find(receipt.id()).orElseThrow();
        var frozen = support.payloads.decode(job.frozenInput(), MemoryLearningState.Frozen.class);
        assertEquals(1, frozen.definition().revision());
        assertEquals(MemoryContracts.LearningPolicy.SUGGEST, frozen.policy());
        assertEquals(
                Set.of(),
                support.frozenSelections.getFirst().visibleCapabilities().orElseThrow());
        assertEquals(
                0, support.frozenSelections.getFirst().budget().orElseThrow().toolCalls());
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.memory.command(support.request("learning/run", Map.of(), Optional.of("stale-run"), 0)));
        var disabled = fixture.save(false, 1, "disable");
        assertEquals(saved.initialSince(), disabled.initialSince());
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.memory.command(
                        support.request("learning/run", Map.of(), Optional.of("disabled-run"), 2)));
        assertEquals(
                1,
                support.payloads
                        .decode(job.frozenInput(), MemoryLearningState.Frozen.class)
                        .definition()
                        .revision());
    }

    @Test
    void disabledDefaultHasNoBindingAndOffPolicyPreventsImmediateRun() throws Exception {
        var fixture = new Fixture();
        fixture.save(false, 0, "disabled");
        assertTrue(fixture.jobs().isEmpty());
        fixture.save(true, 1, "enabled");
        fixture.memory.command(fixture.support.request(
                "settings/update",
                new MemoryContracts.LearningSettingsUpdate(MemoryContracts.LearningPolicy.OFF),
                Optional.of("off"),
                0));
        assertThrows(
                IllegalArgumentException.class,
                () -> fixture.memory.command(
                        fixture.support.request("learning/run", Map.of(), Optional.of("off-run"), 2)));
        var batches = fixture.memory.query(fixture.support.request(
                "learning/batches", new DocumentContracts.PageRequest("", 10), Optional.empty(), 0));
        assertEquals(
                0,
                fixture.support
                        .decode(batches, DocumentContracts.Page.class)
                        .documents()
                        .size());
        var view = fixture.support.decode(
                fixture.memory.query(fixture.support.request(
                        "view.learning.definition",
                        new ViewQueryRequest("learningDefinition", Map.of(), "", 1, Optional.empty()),
                        Optional.empty(),
                        0)),
                ViewQueryResult.class);
        assertEquals(2, view.revision());
        assertTrue(view.values().json().contains("true"));
        assertThrows(IllegalArgumentException.class, () -> fixture.save(true, 1, "new-stale"));
    }

    @Test
    void explicitRepairCreatesOneNewAttemptForFailedBindingAndDoesNotResetFrozenIntent() throws Exception {
        var fixture = new Fixture();
        fixture.save(true, 0, "save");
        var original = fixture.jobs().getFirst();
        fixture.support.jobs.record(new ExtensionJob(
                original.id(),
                original.extensionId(),
                original.workspaceId(),
                original.jobType(),
                original.definitionId(),
                original.definitionRevision(),
                original.frozenInput(),
                ExecutionState.FAILED,
                2,
                original.checkpoint(),
                2,
                Optional.empty(),
                Optional.of("FAILED"),
                original.createdAt(),
                original.updatedAt()));
        var request = fixture.support.request("learning/repair", Map.of(), Optional.of("repair"), 0);
        fixture.memory.command(request);
        fixture.memory.command(request);
        assertEquals(2, fixture.jobs().size());
        assertTrue(fixture.jobs().stream().allMatch(value -> value.frozenInput().equals(original.frozenInput())));
    }

    @Test
    void bindingReplayAcknowledgesCommittedTargetAndRebasesOnlyTheCurrentBindingRevision() throws Exception {
        var fixture = new Fixture();
        fixture.save(true, 0, "save");
        var job = fixture.jobs().getFirst();
        fixture.binding = new ScheduleDefinitionBindingPort.Binding(
                MemoryLearningState.DEFINITION_ID, 3, 0, "", 0, ScheduleDefinitionBindingPort.State.UNBOUND);
        var executor = new MemoryBindingJobExecutor(fixture.runtime());
        assertTrue(executor.plan(job).isPresent());
        executor.execute(execution(fixture.support, job), fixture.support.cancellation);
        assertEquals(1, fixture.bindCalls);
        assertEquals(3, fixture.lastChange.expectedBindingRevision());
        executor.execute(execution(fixture.support, job), fixture.support.cancellation);
        assertEquals(1, fixture.bindCalls);
        assertTrue(fixture.intent().acknowledged());
    }

    @Test
    void deletedGenerationCannotBeResurrectedAndSupersededIntentDoesNotTouchSchedule() throws Exception {
        var fixture = new Fixture();
        fixture.save(true, 0, "save");
        var job = fixture.jobs().getFirst();
        fixture.binding = new ScheduleDefinitionBindingPort.Binding(
                MemoryLearningState.DEFINITION_ID, 2, 1, "deleted", 1, ScheduleDefinitionBindingPort.State.DETACHED);
        var executor = new MemoryBindingJobExecutor(fixture.runtime());
        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(execution(fixture.support, job), fixture.support.cancellation));
        assertFalse(fixture.intent().acknowledged());
        fixture.save(false, 1, "disabled");
        executor.execute(execution(fixture.support, job), fixture.support.cancellation);
        assertEquals(0, fixture.bindCalls);
        assertTrue(fixture.intent().acknowledged());
    }

    @Test
    void disabledDraftSavedLongAgoDoesNotExpandTheFirstEnabledThirtyDayWindow() throws Exception {
        var fixture = new Fixture();
        fixture.support.store.inTransaction(MemoryStoreAccess.ID, transaction -> {
            transaction.put(
                    MemoryLearningState.definitions(fixture.support.workspaceId),
                    MemoryLearningState.DEFINITION_ID,
                    0,
                    fixture.support.payloads.encode(new MemoryV3Contracts.LearningDefinition(
                            MemoryLearningState.DEFINITION_ID,
                            1,
                            "旧草稿",
                            false,
                            ExecutionOverrides.empty(),
                            BuiltinExtensionTestSupport.NOW.minusSeconds(365L * 86400),
                            BuiltinExtensionTestSupport.NOW.minusSeconds(335L * 86400))));
            return null;
        });
        var enabled = fixture.save(true, 1, "first-enable");
        assertEquals(BuiltinExtensionTestSupport.NOW.minusSeconds(30L * 86400), enabled.initialSince());
        fixture.save(false, 2, "disable");
        assertEquals(enabled.initialSince(), fixture.save(true, 3, "reenable").initialSince());
    }

    private static ExtensionJobExecution execution(BuiltinExtensionTestSupport support, ExtensionJob job) {
        var active = new ExtensionJob(
                job.id(),
                job.extensionId(),
                job.workspaceId(),
                job.jobType(),
                job.definitionId(),
                job.definitionRevision(),
                job.frozenInput(),
                ExecutionState.RUNNING,
                job.revision(),
                job.checkpoint(),
                2,
                Optional.of(1L),
                Optional.empty(),
                job.createdAt(),
                job.updatedAt());
        return new ExtensionJobExecution(
                active,
                new ExtensionJobUnit(
                        job.id(),
                        1,
                        "bind",
                        job.frozenInput(),
                        ExtensionJobUnitState.INTENT_RECORDED,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        BuiltinExtensionTestSupport.NOW,
                        Optional.empty()));
    }

    private static final class Fixture {
        private final BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        private final BuiltinExtensionTestSupport.Started memory;
        private ScheduleDefinitionBindingPort.Binding binding = new ScheduleDefinitionBindingPort.Binding(
                MemoryLearningState.DEFINITION_ID, 0, 0, "", 0, ScheduleDefinitionBindingPort.State.UNBOUND);
        private ScheduleDefinitionBindingPort.Change lastChange;
        private int bindCalls;
        private final ScheduleDefinitionBindingPort port = new ScheduleDefinitionBindingPort() {
            @Override
            public Binding read(ExtensionId owner, WorkspaceId workspaceId, String definitionId) {
                return binding;
            }

            @Override
            public Binding bind(
                    ExtensionId owner, WorkspaceId workspaceId, Change change, CancellationToken cancellation) {
                bindCalls++;
                lastChange = change;
                binding = new Binding(
                        change.definitionId(),
                        binding.revision() + 1,
                        binding.generation(),
                        "schedule",
                        change.definitionRevision(),
                        State.BOUND);
                return binding;
            }
        };

        private Fixture() throws Exception {
            var started = support.start(new MemoryExtension());
            var base = started.context();
            var context = new ExtensionExecutionContext(
                    base.extension(),
                    base.workspaceId(),
                    base.effectivePermissions(),
                    base.cancellation(),
                    base.clock(),
                    base.managedStore(),
                    base.turns(),
                    base.executionPolicies(),
                    base.scheduleTargets(),
                    base.inputs(),
                    base.jobs(),
                    base.evidence(),
                    base.attachments(),
                    base.credentials(),
                    base.privateNetworkGrants(),
                    base.services(),
                    base.embeddings(),
                    base.workspaceExecution(),
                    port);
            memory = new BuiltinExtensionTestSupport.Started(started.bundle(), started.contributions(), context);
        }

        private MemoryV3Contracts.LearningDefinition save(boolean enabled, long revision, String key) throws Exception {
            return support.decode(
                    memory.command(support.request(
                            "learning/save",
                            new MemoryV3Contracts.LearningSave(enabled, ExecutionOverrides.empty(), false),
                            Optional.of(key),
                            revision)),
                    MemoryV3Contracts.LearningDefinition.class);
        }

        private MemoryV3Contracts.LearningDefinition saveExecution(ExecutionOverrides execution) throws Exception {
            return support.decode(
                    memory.command(support.request(
                            "learning/save",
                            new MemoryV3Contracts.LearningSave(true, execution, false),
                            Optional.of("save-execution"),
                            0)),
                    MemoryV3Contracts.LearningDefinition.class);
        }

        private ExtensionExecutionReceipt start(
                ExecutionOverrides execution, OrchestrationContracts.ExecutionBudget budget, String key)
                throws Exception {
            return support.decode(
                    memory.orchestrate(support.request(
                            "execution/start",
                            new OrchestrationContracts.StartRequest(
                                    MemoryLearningState.DEFINITION_ID, execution, budget),
                            Optional.of(key),
                            1)),
                    ExtensionExecutionReceipt.class);
        }

        private List<ExtensionJob> jobs() {
            return support.jobs.list(Optional.empty(), Optional.empty(), Set.of(), 100);
        }

        private MemoryLearningState.BindingIntent intent() throws Exception {
            return support.store.inTransaction(
                    MemoryStoreAccess.ID,
                    transaction -> support.payloads.decode(
                            transaction
                                    .get(MemoryLearningState.intents(support.workspaceId), "binding-1")
                                    .orElseThrow()
                                    .payload(),
                            MemoryLearningState.BindingIntent.class));
        }

        private ExtensionJobRuntimeContext runtime() {
            return new ExtensionJobRuntimeContext(
                    support.clock,
                    support.payloads,
                    support.turns,
                    support.store,
                    memory.context().services(),
                    support.embeddings,
                    AutomationStepPort.unavailable(),
                    ScheduledCommandPort.unavailable(),
                    ScheduleLifecyclePort.unavailable(),
                    ConversationEvidencePort.unavailable(),
                    port);
        }
    }
}
