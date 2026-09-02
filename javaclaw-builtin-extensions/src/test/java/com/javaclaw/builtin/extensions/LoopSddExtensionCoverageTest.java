package com.javaclaw.builtin.extensions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.LoopContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.SddContracts;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;

import static com.javaclaw.builtin.extensions.BuiltinExtensionTestSupport.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoopSddExtensionCoverageTest {
    private static final AgentProfileRef PROFILE = new AgentProfileRef("profile", 1);
    private static final OrchestrationContracts.ExecutionBudget BUDGET =
            new OrchestrationContracts.ExecutionBudget(5, 1_000, 500, 10);

    @Test
    void loopExplicitAndCurrentConfirmationResumeExactWaitingIteration() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new LoopExtension());
        ExtensionJob explicit = loopJob(support, "loop-explicit", confirmationLoop(), waitingLoop(1));
        support.jobs.record(explicit);

        var explicitResponse = started.command(support.request(
                "execution/confirm",
                new LoopContracts.Confirmation(explicit.id(), 1, true),
                Optional.of("confirm-explicit"),
                explicit.revision()));
        ExtensionJob resumed = support.jobs.find(explicit.id()).orElseThrow();

        assertEquals(4, explicitResponse.revision());
        assertEquals(ExecutionState.RUNNING, resumed.state());
        assertEquals(2, loopCheckpoint(support, resumed).iteration());

        ExtensionJob current = loopJob(support, "loop-current", confirmationLoop(), waitingLoop(2));
        support.jobs.record(current);
        started.command(support.request(
                "execution/confirm-current",
                Map.of("jobId", current.id()),
                Optional.of("confirm-current"),
                current.revision()));
        assertEquals(
                3,
                loopCheckpoint(support, support.jobs.find(current.id()).orElseThrow())
                        .iteration());
    }

    @Test
    void loopRejectionCancelsAndConfirmationRequiresIdempotencyAndExistingJob() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new LoopExtension());
        ExtensionJob rejected = loopJob(support, "loop-reject", confirmationLoop(), waitingLoop(1));
        support.jobs.record(rejected);

        started.command(support.request(
                "execution/reject-current",
                Map.of("jobId", rejected.id()),
                Optional.of("reject-current"),
                rejected.revision()));

        assertEquals(
                ExecutionState.CANCELLED,
                support.jobs.find(rejected.id()).orElseThrow().state());
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "execution/confirm", new LoopContracts.Confirmation("missing", 1, true), Optional.empty(), 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "execution/confirm",
                        new LoopContracts.Confirmation("missing", 1, true),
                        Optional.of("missing"),
                        1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "execution/confirm-current", Map.of("jobId", "missing"), Optional.of("missing-current"), 1)));
    }

    @Test
    void loopConfirmationRejectsWrongOwnershipStateRuleAndIteration() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new LoopExtension());
        assertLoopTargetRejected(
                support,
                started,
                replaceLoopIdentity(
                        loopJob(support, "wrong-extension", confirmationLoop(), waitingLoop(1)),
                        new ExtensionId(BuiltinExtensionIds.PLAN),
                        support.workspaceId,
                        AutomationExecutionResource.JOB_TYPE,
                        ExecutionState.WAITING_INPUT),
                1);
        assertLoopTargetRejected(
                support,
                started,
                replaceLoopIdentity(
                        loopJob(support, "wrong-workspace", confirmationLoop(), waitingLoop(1)),
                        new ExtensionId(BuiltinExtensionIds.LOOP),
                        WorkspaceId.random(),
                        AutomationExecutionResource.JOB_TYPE,
                        ExecutionState.WAITING_INPUT),
                1);
        assertLoopTargetRejected(
                support,
                started,
                replaceLoopIdentity(
                        loopJob(support, "wrong-kind", confirmationLoop(), waitingLoop(1)),
                        new ExtensionId(BuiltinExtensionIds.LOOP),
                        support.workspaceId,
                        "other-job",
                        ExecutionState.WAITING_INPUT),
                1);
        assertLoopTargetRejected(
                support,
                started,
                replaceLoopIdentity(
                        loopJob(support, "wrong-state", confirmationLoop(), waitingLoop(1)),
                        new ExtensionId(BuiltinExtensionIds.LOOP),
                        support.workspaceId,
                        AutomationExecutionResource.JOB_TYPE,
                        ExecutionState.RUNNING),
                1);
        assertLoopTargetRejected(support, started, loopJob(support, "tool-rule", toolLoop(), waitingLoop(1)), 1);
        assertLoopTargetRejected(support, started, loopJob(support, "too-late", confirmationLoop(), waitingLoop(1)), 6);
        assertLoopTargetRejected(
                support, started, loopJob(support, "wrong-iteration", confirmationLoop(), waitingLoop(2)), 1);
    }

    @Test
    void loopCurrentConfirmationRejectsCheckpointThatIsNotWaiting() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new LoopExtension());
        ExtensionJob job = loopJob(
                support,
                "not-waiting",
                confirmationLoop(),
                new LoopJobExecutor.Checkpoint(1, "digest", 0, Optional.empty()));
        support.jobs.record(job);

        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "execution/confirm-current",
                        Map.of("jobId", job.id()),
                        Optional.of("not-waiting"),
                        job.revision())));
    }

    @Test
    void sddCurrentApprovalsDeriveSpecificationAndTaskDigestsFromFrozenDefinition() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SddExtension());
        SddContracts.Definition definition = sddDefinition();
        ExtensionJob specification = sddJob(
                support, "sdd-spec", definition, sddCheckpoint(SddContracts.Phase.SPECIFICATION_APPROVAL, definition));
        support.jobs.record(specification);

        started.command(support.request(
                "execution/approve-current",
                Map.of("jobId", specification.id()),
                Optional.of("approve-spec"),
                specification.revision()));
        assertEquals(
                SddContracts.Phase.DESIGN,
                sddCheckpoint(support, support.jobs.find(specification.id()).orElseThrow())
                        .phase());

        ExtensionJob tasks =
                sddJob(support, "sdd-tasks", definition, sddCheckpoint(SddContracts.Phase.TASK_APPROVAL, definition));
        support.jobs.record(tasks);
        started.command(support.request(
                "execution/approve-current",
                Map.of("jobId", tasks.id()),
                Optional.of("approve-tasks"),
                tasks.revision()));
        assertEquals(
                SddContracts.Phase.IMPLEMENT,
                sddCheckpoint(support, support.jobs.find(tasks.id()).orElseThrow())
                        .phase());
    }

    @Test
    void sddExplicitApprovalRequiresKeyExistingOwnedWaitingJobAndCurrentPhase() throws Exception {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        var started = support.start(new SddExtension());
        SddContracts.Definition definition = sddDefinition();
        ExtensionJob job = sddJob(
                support,
                "sdd-explicit",
                definition,
                sddCheckpoint(SddContracts.Phase.SPECIFICATION_APPROVAL, definition));
        support.jobs.record(job);
        SddContracts.Approval approval = new SddContracts.Approval(
                job.id(), SddContracts.ApprovalKind.SPECIFICATION, definition.specificationDigest());

        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(
                        support.request("execution/approve", approval, Optional.empty(), job.revision())));
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "execution/approve-current", Map.of("jobId", "missing"), Optional.of("missing"), 1)));

        ExtensionJob notApproval =
                sddJob(support, "sdd-design", definition, sddCheckpoint(SddContracts.Phase.DESIGN, definition));
        support.jobs.record(notApproval);
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "execution/approve-current",
                        Map.of("jobId", notApproval.id()),
                        Optional.of("wrong-phase"),
                        notApproval.revision())));
    }

    @Test
    void sddApprovalRejectsWrongOwnershipStateAndEveryStaleTaskDigestCondition() {
        BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        SddContracts.Definition definition = sddDefinition();
        assertThrows(
                IllegalArgumentException.class,
                () -> SddExtension.approved(
                        definition,
                        sddCheckpoint(SddContracts.Phase.DESIGN, definition),
                        new SddContracts.Approval(
                                "job", SddContracts.ApprovalKind.SPECIFICATION, definition.specificationDigest())));
        assertThrows(
                IllegalArgumentException.class,
                () -> SddExtension.approved(
                        definition,
                        sddCheckpoint(SddContracts.Phase.SPECIFICATION_APPROVAL, definition),
                        new SddContracts.Approval("job", SddContracts.ApprovalKind.SPECIFICATION, "a".repeat(64))));
        assertTaskApprovalRejected(
                definition, sddCheckpoint(SddContracts.Phase.DESIGN, definition), definition.taskDigest());
        SddContracts.Checkpoint noSpecification = new SddContracts.Checkpoint(
                SddContracts.Phase.TASK_APPROVAL, 0, Optional.empty(), Optional.empty(), 0, false);
        assertTaskApprovalRejected(definition, noSpecification, definition.taskDigest());
        SddContracts.Checkpoint staleSpecification = new SddContracts.Checkpoint(
                SddContracts.Phase.TASK_APPROVAL, 0, Optional.of("a".repeat(64)), Optional.empty(), 0, false);
        assertTaskApprovalRejected(definition, staleSpecification, definition.taskDigest());
        assertTaskApprovalRejected(
                definition, sddCheckpoint(SddContracts.Phase.TASK_APPROVAL, definition), "b".repeat(64));
    }

    private static void assertLoopTargetRejected(
            BuiltinExtensionTestSupport support,
            BuiltinExtensionTestSupport.Started started,
            ExtensionJob job,
            int iteration) {
        support.jobs.record(job);
        assertThrows(
                IllegalArgumentException.class,
                () -> started.command(support.request(
                        "execution/confirm",
                        new LoopContracts.Confirmation(job.id(), iteration, true),
                        Optional.of("reject-" + job.id()),
                        job.revision())));
    }

    private static void assertTaskApprovalRejected(
            SddContracts.Definition definition, SddContracts.Checkpoint checkpoint, String digest) {
        assertThrows(
                IllegalArgumentException.class,
                () -> SddExtension.approved(
                        definition,
                        checkpoint,
                        new SddContracts.Approval("job", SddContracts.ApprovalKind.TASKS, digest)));
    }

    private static ExtensionJob loopJob(
            BuiltinExtensionTestSupport support,
            String id,
            LoopContracts.Definition definition,
            LoopJobExecutor.Checkpoint checkpoint) {
        var frozen = new OrchestrationContracts.FrozenExecution(
                support.executionSnapshot(PROFILE), Optional.empty(), support.payloads.encode(definition), BUDGET);
        return waitingJob(
                support,
                id,
                new ExtensionId(BuiltinExtensionIds.LOOP),
                support.workspaceId,
                support.payloads.encode(frozen),
                support.payloads.encode(checkpoint),
                ExecutionState.WAITING_INPUT);
    }

    private static ExtensionJob sddJob(
            BuiltinExtensionTestSupport support,
            String id,
            SddContracts.Definition definition,
            SddContracts.Checkpoint checkpoint) {
        var frozen = new OrchestrationContracts.FrozenExecution(
                support.executionSnapshot(PROFILE), Optional.empty(), support.payloads.encode(definition), BUDGET);
        return waitingJob(
                support,
                id,
                new ExtensionId(BuiltinExtensionIds.SDD),
                support.workspaceId,
                support.payloads.encode(frozen),
                support.payloads.encode(checkpoint),
                ExecutionState.WAITING_APPROVAL);
    }

    private static ExtensionJob waitingJob(
            BuiltinExtensionTestSupport support,
            String id,
            ExtensionId extensionId,
            WorkspaceId workspaceId,
            com.javaclaw.api.CanonicalPayload frozen,
            com.javaclaw.api.CanonicalPayload domainCheckpoint,
            ExecutionState state) {
        var shared = new OrchestrationContracts.ExecutionCheckpoint(
                domainCheckpoint, OrchestrationContracts.ExecutionConsumption.zero());
        return new ExtensionJob(
                id,
                extensionId,
                workspaceId,
                AutomationExecutionResource.JOB_TYPE,
                extensionId.value().endsWith("loop") ? "loop" : "sdd",
                1,
                frozen,
                state,
                3,
                support.payloads.encode(shared),
                1,
                Optional.empty(),
                Optional.empty(),
                NOW,
                NOW);
    }

    private static ExtensionJob replaceLoopIdentity(
            ExtensionJob job, ExtensionId extensionId, WorkspaceId workspaceId, String jobType, ExecutionState state) {
        return new ExtensionJob(
                job.id(),
                extensionId,
                workspaceId,
                jobType,
                job.definitionId(),
                job.definitionRevision(),
                job.frozenInput(),
                state,
                job.revision(),
                job.checkpoint(),
                job.nextUnitSequence(),
                job.activeUnitSequence(),
                job.errorCode(),
                job.createdAt(),
                job.updatedAt());
    }

    private static LoopJobExecutor.Checkpoint loopCheckpoint(BuiltinExtensionTestSupport support, ExtensionJob job) {
        var shared = support.payloads.decode(job.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
        return support.payloads.decode(shared.domain(), LoopJobExecutor.Checkpoint.class);
    }

    private static SddContracts.Checkpoint sddCheckpoint(BuiltinExtensionTestSupport support, ExtensionJob job) {
        var shared = support.payloads.decode(job.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
        return support.payloads.decode(shared.domain(), SddContracts.Checkpoint.class);
    }

    private static LoopJobExecutor.Checkpoint waitingLoop(int iteration) {
        return new LoopJobExecutor.Checkpoint(iteration, "digest", 0, Optional.of(iteration));
    }

    private static LoopContracts.Definition confirmationLoop() {
        LoopContracts.VerificationRule rule = new LoopContracts.VerificationRule(
                LoopContracts.VerificationKind.USER_CONFIRMATION,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        return new LoopContracts.Definition("loop", 1, "Loop", "目标", "执行", 5, 2, rule, NOW);
    }

    private static LoopContracts.Definition toolLoop() {
        LoopContracts.VerificationRule rule = new LoopContracts.VerificationRule(
                LoopContracts.VerificationKind.TOOL_EXIT_CODE,
                Optional.of("verify"),
                Optional.of(0),
                Optional.empty(),
                Optional.empty());
        return new LoopContracts.Definition("loop", 1, "Loop", "目标", "执行", 5, 2, rule, NOW);
    }

    private static SddContracts.Checkpoint sddCheckpoint(SddContracts.Phase phase, SddContracts.Definition definition) {
        Optional<String> specification = phase == SddContracts.Phase.TASK_APPROVAL || phase == SddContracts.Phase.DESIGN
                ? Optional.of(definition.specificationDigest())
                : Optional.empty();
        return new SddContracts.Checkpoint(phase, 0, specification, Optional.empty(), 0, false);
    }

    private static SddContracts.Definition sddDefinition() {
        SddContracts.VerificationRule rule = new SddContracts.VerificationRule(
                SddContracts.VerificationKind.TOOL_EXIT_CODE,
                "verify",
                Optional.of(0),
                Optional.empty(),
                Optional.empty());
        return new SddContracts.Definition(
                "sdd", 1, "Feature", new SddContracts.Content("验收", "设计", List.of("任务")), rule, 1, NOW);
    }
}
