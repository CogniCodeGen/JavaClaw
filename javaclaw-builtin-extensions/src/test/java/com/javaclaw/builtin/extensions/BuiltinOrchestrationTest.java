package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.ContractDigests;
import com.javaclaw.builtin.contracts.LoopContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.PlanContracts;
import com.javaclaw.builtin.contracts.SddContracts;
import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobExecution;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionJobUnit;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.extension.spi.ScheduledCommandPort;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinOrchestrationTest {
    private static final AgentProfileRef PROFILE = new AgentProfileRef("profile", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(10, 10_000, 10_000, 100);

    @Test
    void planStartOnlyQueuesFrozenExecutionAndDoesNotRunTurnInline() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        PlanExtension extension = new PlanExtension();
        var started = support.start(extension);
        PlanContracts.Definition definition = plan();
        started.command(support.request("definition/create", planManagementInput(), Optional.of("create-plan"), 0));

        ExtensionExecutionReceipt receipt =
                publicReceipt(support, started.orchestrate(start(support, "plan", "start-plan", 1)));
        ExtensionJob job = support.jobs.find(receipt.id()).orElseThrow();

        assertEquals(ExecutionState.QUEUED, receipt.state());
        assertEquals(definition.revision(), receipt.definitionRevision());
        assertTrue(support.turns.commands().isEmpty());
        var frozen = support.payloads.decode(job.frozenInput(), OrchestrationContracts.FrozenExecution.class);
        assertEquals(
                support.executionSnapshot(PROFILE).toolCatalog().digest(),
                frozen.platform().toolCatalog().digest());
    }

    @Test
    void planExecutorAdvancesExactlyOneUnitAndPersistsBudgetConsumption() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        PlanJobExecutor executor = new PlanJobExecutor(runtime(support));
        ExtensionJob job = job(
                support,
                "plan",
                support.payloads.encode(plan()),
                support.payloads.encode(new PlanJobExecutor.Checkpoint(0)));
        var work = executor.plan(job).orElseThrow();
        ExtensionJob running = active(job);
        ExtensionJobUnit unit = unit(running, work);

        var result = executor.execute(new ExtensionJobExecution(running, unit), new CancellationSource());
        var checkpoint = support.payloads.decode(result.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);

        assertEquals("build", work.unitId());
        assertEquals(ExecutionState.RUNNING, result.nextState());
        assertEquals(1, checkpoint.consumption().turns());
        assertEquals(1, support.turns.commands().size());
    }

    @Test
    void loopToolVerificationRejectsMissingEvidenceAndUserRuleWaitsForConfirmation() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        LoopJobExecutor executor = new LoopJobExecutor(runtime(support));
        LoopContracts.Definition toolLoop = loop(toolExitRule());
        ExtensionJob job = job(
                support,
                "loop",
                support.payloads.encode(toolLoop),
                support.payloads.encode(LoopJobExecutor.Checkpoint.initial()));
        var work = executor.plan(job).orElseThrow();

        assertThrows(
                IllegalStateException.class,
                () -> executor.execute(
                        new ExtensionJobExecution(active(job), unit(active(job), work)), new CancellationSource()));

        LoopContracts.Definition confirmationLoop = loop(userRule());
        ExtensionJob waitingJob = job(
                support,
                "loop-confirm",
                support.payloads.encode(confirmationLoop),
                support.payloads.encode(LoopJobExecutor.Checkpoint.initial()));
        var confirmationWork = executor.plan(waitingJob).orElseThrow();
        ExtensionJob active = active(waitingJob);
        var waiting = executor.execute(
                new ExtensionJobExecution(active, unit(active, confirmationWork)), new CancellationSource());
        assertEquals(ExecutionState.WAITING_INPUT, waiting.nextState());
    }

    @Test
    void workflowAcceptsSafeGraphAndQueuesFrozenExecution() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        WorkflowExtension extension = new WorkflowExtension();
        var started = support.start(extension);
        WorkflowContracts.Definition definition = workflowWithTool();
        started.command(support.request(
                WorkflowManagement.SAVE,
                new WorkflowDefinitionMapper(support.payloads).management(definition),
                Optional.of("put-workflow"),
                0));

        ExtensionExecutionReceipt receipt =
                publicReceipt(support, started.orchestrate(start(support, "workflow", "start-workflow", 1)));

        assertTrue(started.contributions().stream()
                .anyMatch(com.javaclaw.extension.spi.ExtensionContributions.Orchestrator.class::isInstance));
        assertEquals(ExecutionState.QUEUED, receipt.state());
        assertEquals(definition.revision(), receipt.definitionRevision());
    }

    @Test
    void sddAdvancesThroughDigestBoundApprovalStages() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SddJobExecutor executor = new SddJobExecutor(runtime(support));
        SddContracts.Definition definition = sdd();
        ExtensionJob proposal = sddJob(
                support,
                definition,
                new SddContracts.Checkpoint(
                        SddContracts.Phase.PROPOSAL, 0, Optional.empty(), Optional.empty(), 0, false));

        var proposalStep = executor.execute(
                new ExtensionJobExecution(
                        active(proposal),
                        unit(active(proposal), executor.plan(proposal).orElseThrow())),
                new CancellationSource());
        SddContracts.Checkpoint approval = sddCheckpoint(support, proposalStep.checkpoint());

        assertEquals(ExecutionState.WAITING_APPROVAL, proposalStep.nextState());
        assertEquals(SddContracts.Phase.SPECIFICATION_APPROVAL, approval.phase());

        ExtensionJob design = sddJob(
                support,
                definition,
                new SddContracts.Checkpoint(
                        SddContracts.Phase.DESIGN,
                        0,
                        Optional.of(definition.specificationDigest()),
                        Optional.empty(),
                        0,
                        false));
        var designStep = executor.execute(
                new ExtensionJobExecution(
                        active(design),
                        unit(active(design), executor.plan(design).orElseThrow())),
                new CancellationSource());

        assertEquals(ExecutionState.WAITING_APPROVAL, designStep.nextState());
        assertEquals(
                SddContracts.Phase.TASK_APPROVAL,
                sddCheckpoint(support, designStep.checkpoint()).phase());
    }

    @Test
    void sddRejectsStaleDigestAndPrematureArchive() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SddJobExecutor executor = new SddJobExecutor(runtime(support));
        SddContracts.Definition definition = sdd();
        ExtensionJob stale = sddJob(
                support,
                definition,
                new SddContracts.Checkpoint(
                        SddContracts.Phase.DESIGN, 0, Optional.of("a".repeat(64)), Optional.empty(), 0, false));

        assertThrows(IllegalStateException.class, () -> executeNext(executor, stale));

        ExtensionJob premature = sddJob(
                support,
                definition,
                new SddContracts.Checkpoint(
                        SddContracts.Phase.ARCHIVE,
                        1,
                        Optional.of(definition.specificationDigest()),
                        Optional.of(definition.taskDigest()),
                        0,
                        false));
        assertThrows(IllegalStateException.class, () -> executor.plan(premature));
    }

    private static com.javaclaw.extension.spi.ExtensionRequest start(
            BuiltinExtensionTestSupport support, String id, String key, long revision) {
        var request = new OrchestrationContracts.StartRequest(id, PROFILE, BUDGET);
        return support.request("execution/start", request, Optional.of(key), revision);
    }

    private static ExtensionExecutionReceipt publicReceipt(
            BuiltinExtensionTestSupport support, com.javaclaw.extension.spi.ExtensionResponse response) {
        Map<?, ?> payload = support.payloads.decode(response.payload(), Map.class);
        assertEquals(
                Set.of(
                        "id",
                        "extensionId",
                        "workspaceId",
                        "jobType",
                        "definitionId",
                        "definitionRevision",
                        "state",
                        "revision",
                        "errorCode",
                        "createdAt",
                        "updatedAt"),
                payload.keySet());
        assertFalse(payload.containsKey("frozenInput"));
        assertFalse(payload.containsKey("checkpoint"));
        return support.decode(response, ExtensionExecutionReceipt.class);
    }

    private static ExtensionJobRuntimeContext runtime(BuiltinExtensionTestSupport support) {
        return new ExtensionJobRuntimeContext(
                support.clock,
                support.payloads,
                support.turns,
                support.store,
                invocation -> {
                    throw new IllegalStateException("isolated service is not configured");
                },
                support.embeddings,
                AutomationStepPort.unavailable(),
                ScheduledCommandPort.unavailable(),
                com.javaclaw.extension.spi.ScheduleLifecyclePort.unavailable());
    }

    private static ExtensionJob job(
            BuiltinExtensionTestSupport support,
            String id,
            com.javaclaw.api.CanonicalPayload definition,
            com.javaclaw.api.CanonicalPayload domainCheckpoint) {
        var frozen = new OrchestrationContracts.FrozenExecution(
                support.executionSnapshot(PROFILE), Optional.empty(), definition, BUDGET);
        var checkpoint = new OrchestrationContracts.ExecutionCheckpoint(
                domainCheckpoint, OrchestrationContracts.ExecutionConsumption.zero());
        return new ExtensionJob(
                id,
                new com.javaclaw.extension.spi.ExtensionId(id.startsWith("plan") ? "javaclaw.plan" : "javaclaw.loop"),
                support.workspaceId,
                AutomationExecutionResource.JOB_TYPE,
                id.startsWith("plan") ? "plan" : "loop",
                1,
                support.payloads.encode(frozen),
                ExecutionState.RUNNING,
                1,
                support.payloads.encode(checkpoint),
                1,
                Optional.empty(),
                Optional.empty(),
                NOW,
                NOW);
    }

    private static ExtensionJob sddJob(
            BuiltinExtensionTestSupport support,
            SddContracts.Definition definition,
            SddContracts.Checkpoint checkpoint) {
        var frozen = new OrchestrationContracts.FrozenExecution(
                support.executionSnapshot(PROFILE), Optional.empty(), support.payloads.encode(definition), BUDGET);
        var shared = new OrchestrationContracts.ExecutionCheckpoint(
                support.payloads.encode(checkpoint), OrchestrationContracts.ExecutionConsumption.zero());
        return new ExtensionJob(
                "sdd-job",
                new com.javaclaw.extension.spi.ExtensionId(BuiltinExtensionIds.SDD),
                support.workspaceId,
                AutomationExecutionResource.JOB_TYPE,
                definition.id(),
                definition.revision(),
                support.payloads.encode(frozen),
                ExecutionState.RUNNING,
                1,
                support.payloads.encode(shared),
                1,
                Optional.empty(),
                Optional.empty(),
                NOW,
                NOW);
    }

    private static SddContracts.Checkpoint sddCheckpoint(
            BuiltinExtensionTestSupport support, com.javaclaw.api.CanonicalPayload payload) {
        var shared = support.payloads.decode(payload, OrchestrationContracts.ExecutionCheckpoint.class);
        return support.payloads.decode(shared.domain(), SddContracts.Checkpoint.class);
    }

    private static void executeNext(SddJobExecutor executor, ExtensionJob job) throws Exception {
        ExtensionJob active = active(job);
        executor.execute(
                new ExtensionJobExecution(
                        active, unit(active, executor.plan(job).orElseThrow())),
                new CancellationSource());
    }

    private static ExtensionJob active(ExtensionJob job) {
        return new ExtensionJob(
                job.id(),
                job.extensionId(),
                job.workspaceId(),
                job.jobType(),
                job.definitionId(),
                job.definitionRevision(),
                job.frozenInput(),
                ExecutionState.RUNNING,
                2,
                job.checkpoint(),
                2,
                Optional.of(1L),
                Optional.empty(),
                job.createdAt(),
                job.updatedAt());
    }

    private static ExtensionJobUnit unit(ExtensionJob active, com.javaclaw.extension.spi.ExtensionJobWorkUnit work) {
        return new ExtensionJobUnit(
                active.id(),
                1,
                work.unitId(),
                work.intent(),
                ExtensionJobUnitState.INTENT_RECORDED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                NOW,
                Optional.empty());
    }

    private static PlanContracts.Definition plan() {
        String question = "database?";
        String hash = ContractDigests.sha256(question);
        return new PlanContracts.Definition(
                "plan",
                1,
                "Upgrade",
                "all gates pass",
                "core",
                List.of("rollback"),
                List.of(new PlanContracts.OpenQuestion(
                        "database", question, hash, Optional.of(new PlanContracts.Decision(hash, "H2")))),
                List.of(
                        new PlanContracts.Step("build", "Build", "build", "build passes", List.of()),
                        new PlanContracts.Step("verify", "Verify", "verify", "tests pass", List.of("build"))),
                NOW);
    }

    private static PlanContracts.ManagementSaveRequest planManagementInput() {
        return new PlanContracts.ManagementSaveRequest(
                "plan",
                "Upgrade",
                "all gates pass",
                "core",
                List.of(new PlanContracts.ManagementRisk("risk-1", "rollback")),
                List.of(new PlanContracts.ManagementOpenQuestion(
                        "question-1", "database", "database?", Optional.of("H2"))),
                List.of(
                        new PlanContracts.ManagementStep("build", "build", "Build", "build", "build passes", List.of()),
                        new PlanContracts.ManagementStep(
                                "verify", "verify", "Verify", "verify", "tests pass", List.of("build"))));
    }

    private static LoopContracts.Definition loop(LoopContracts.VerificationRule rule) {
        return new LoopContracts.Definition("loop", 1, "Improve", "verified", "work", 2, 2, rule, NOW);
    }

    private static LoopContracts.VerificationRule toolExitRule() {
        return new LoopContracts.VerificationRule(
                LoopContracts.VerificationKind.TOOL_EXIT_CODE,
                Optional.of("command"),
                Optional.of(0),
                Optional.empty(),
                Optional.empty());
    }

    private static LoopContracts.VerificationRule userRule() {
        return new LoopContracts.VerificationRule(
                LoopContracts.VerificationKind.USER_CONFIRMATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static WorkflowContracts.Definition workflowWithTool() {
        var start = new WorkflowContracts.Node(
                "start",
                WorkflowContracts.NodeKind.START,
                "Start",
                Optional.empty(),
                WorkflowContracts.NodeConfig.empty());
        var tool = new WorkflowContracts.Node(
                "tool",
                WorkflowContracts.NodeKind.TOOL,
                "Tool",
                Optional.empty(),
                new WorkflowContracts.NodeConfig(
                        Optional.of(
                                new WorkflowContracts.ToolConfig("javaclaw.verify", supportPayload(), "verification")),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty()));
        var end = new WorkflowContracts.Node(
                "end", WorkflowContracts.NodeKind.END, "End", Optional.empty(), WorkflowContracts.NodeConfig.empty());
        return new WorkflowContracts.Definition(
                "workflow",
                1,
                "Release",
                List.of(start, tool, end),
                List.of(
                        new WorkflowContracts.Edge("start", "tool", Optional.empty()),
                        new WorkflowContracts.Edge("tool", "end", Optional.empty())),
                10,
                NOW);
    }

    private static com.javaclaw.api.CanonicalPayload supportPayload() {
        return new com.javaclaw.api.CanonicalPayload("{}");
    }

    private static SddContracts.Definition sdd() {
        return new SddContracts.Definition(
                "sdd",
                1,
                "Release",
                new SddContracts.Content("all checks pass", "use governed turns", List.of("implement")),
                new SddContracts.VerificationRule(
                        SddContracts.VerificationKind.TOOL_EXIT_CODE,
                        "verify",
                        Optional.of(0),
                        Optional.empty(),
                        Optional.empty()),
                1,
                NOW);
    }
}
