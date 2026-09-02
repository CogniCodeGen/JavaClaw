package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
import com.javaclaw.builtin.contracts.SddContracts;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContext;
import com.javaclaw.extension.spi.ExtensionContribution;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobMutation;
import com.javaclaw.extension.spi.ExtensionJobRegistration;
import com.javaclaw.extension.spi.ExtensionJobRuntimeContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ViewAction;

/** SDD Definition 与两次 SHA-256 审批绑定的可恢复阶段状态机。 */
final class SddExtension implements ExtensionBundle {
    private final ManagedDocumentResource<SddContracts.Definition> documents = new ManagedDocumentResource<>(
            BuiltinExtensionIds.SDD,
            "规格驱动开发",
            SddContracts.Definition.class,
            Set.of(ContributionKind.ORCHESTRATOR, ContributionKind.SCHEDULABLE_ACTION));
    private final SddManagement management = new SddManagement(documents);
    private final AutomationExecutionResource<SddContracts.Definition> executions =
            new AutomationExecutionResource<>(documents, "规格驱动开发", SddJobExecutor::new, this::initialCheckpoint);

    @Override
    public ExtensionDescriptor descriptor() {
        return documents.descriptor();
    }

    @Override
    public List<ExtensionContribution> start(ExtensionContext context) {
        List<ExtensionContribution> contributions = new ArrayList<>(documents.startWithManagedWrites(context));
        contributions.addAll(management.contributions());
        contributions.addAll(executions.contributions(
                List.of(new ExtensionContributions.Command(
                        "execution.approve", Set.of("execution/approve", "execution/approve-current"), this::approve)),
                List.of(new ViewAction(
                        "批准当前阶段",
                        "execution/approve-current",
                        Map.of(),
                        Map.of("jobId", "id"),
                        new ExpectedRevisionBinding.RowField("revision"),
                        true))));
        return List.copyOf(contributions);
    }

    @Override
    public List<ExtensionSchema> schemas() {
        List<ExtensionSchema> schemas = new ArrayList<>(documents.schemas());
        schemas.addAll(executions.schemas());
        return List.copyOf(schemas);
    }

    @Override
    public List<ExtensionJobRegistration> jobExecutors(ExtensionJobRuntimeContext context) {
        return executions.jobExecutors(context);
    }

    @Override
    public void close() {
        documents.close();
    }

    private CanonicalPayload initialCheckpoint(SddContracts.Definition definition) {
        return documents
                .payloads()
                .encode(new SddContracts.Checkpoint(
                        SddContracts.Phase.PROPOSAL, 0, Optional.empty(), Optional.empty(), 0, false));
    }

    private ExtensionResponse approve(ExtensionRequest request, ExtensionExecutionContext context) {
        SddContracts.Approval approval =
                switch (request.operation()) {
                    case "execution/approve" ->
                        documents.payloads().decode(request.payload(), SddContracts.Approval.class);
                    case "execution/approve-current" -> currentApproval(request, context);
                    default -> throw new IllegalArgumentException("unknown SDD approval operation");
                };
        ExtensionJob job = requireWaitingJob(approval.jobId(), request, context);
        OrchestrationContracts.FrozenExecution frozen =
                documents.payloads().decode(job.frozenInput(), OrchestrationContracts.FrozenExecution.class);
        SddContracts.Definition definition =
                documents.payloads().decode(frozen.definition(), SddContracts.Definition.class);
        OrchestrationContracts.ExecutionCheckpoint shared =
                documents.payloads().decode(job.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
        SddContracts.Checkpoint checkpoint =
                documents.payloads().decode(shared.domain(), SddContracts.Checkpoint.class);
        SddContracts.Checkpoint approved = approved(definition, checkpoint, approval);
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("SDD approval requires idempotency key"));
        ExtensionJob updated = context.jobs()
                .continueWaiting(
                        job.id(),
                        ExecutionState.WAITING_APPROVAL,
                        documents
                                .payloads()
                                .encode(new OrchestrationContracts.ExecutionCheckpoint(
                                        documents.payloads().encode(approved), shared.consumption())),
                        new ExtensionJobMutation(key, request.expectedRevision()));
        return new ExtensionResponse(
                documents.payloads().encode(ExtensionExecutionReceipt.from(updated)), updated.revision());
    }

    private SddContracts.Approval currentApproval(ExtensionRequest request, ExtensionExecutionContext context) {
        ExecutionKey input = documents.payloads().decode(request.payload(), ExecutionKey.class);
        ExtensionJob job = requireWaitingJob(input.jobId(), request, context);
        OrchestrationContracts.FrozenExecution frozen =
                documents.payloads().decode(job.frozenInput(), OrchestrationContracts.FrozenExecution.class);
        SddContracts.Definition definition =
                documents.payloads().decode(frozen.definition(), SddContracts.Definition.class);
        OrchestrationContracts.ExecutionCheckpoint shared =
                documents.payloads().decode(job.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
        SddContracts.Checkpoint checkpoint =
                documents.payloads().decode(shared.domain(), SddContracts.Checkpoint.class);
        return switch (checkpoint.phase()) {
            case SPECIFICATION_APPROVAL ->
                new SddContracts.Approval(
                        job.id(), SddContracts.ApprovalKind.SPECIFICATION, definition.specificationDigest());
            case TASK_APPROVAL ->
                new SddContracts.Approval(job.id(), SddContracts.ApprovalKind.TASKS, definition.taskDigest());
            default -> throw new IllegalArgumentException("SDD Execution is not awaiting a content approval");
        };
    }

    private ExtensionJob requireWaitingJob(String jobId, ExtensionRequest request, ExtensionExecutionContext context) {
        ExtensionJob job =
                context.jobs().find(jobId).orElseThrow(() -> new IllegalArgumentException("SDD Job does not exist"));
        boolean owned = job.extensionId().equals(documents.extensionId())
                && job.workspaceId().equals(request.workspaceId())
                && job.jobType().equals(AutomationExecutionResource.JOB_TYPE);
        if (!owned || job.state() != ExecutionState.WAITING_APPROVAL) {
            throw new IllegalArgumentException("SDD Job is not waiting for approval");
        }
        return job;
    }

    static SddContracts.Checkpoint approved(
            SddContracts.Definition definition, SddContracts.Checkpoint checkpoint, SddContracts.Approval approval) {
        return switch (approval.kind()) {
            case SPECIFICATION -> approveSpecification(definition, checkpoint, approval.contentDigest());
            case TASKS -> approveTasks(definition, checkpoint, approval.contentDigest());
        };
    }

    private static SddContracts.Checkpoint approveSpecification(
            SddContracts.Definition definition, SddContracts.Checkpoint checkpoint, String digest) {
        if (checkpoint.phase() != SddContracts.Phase.SPECIFICATION_APPROVAL
                || !definition.specificationDigest().equals(digest)) {
            throw new IllegalArgumentException("SDD specification approval does not match current content");
        }
        return new SddContracts.Checkpoint(
                SddContracts.Phase.DESIGN,
                checkpoint.nextTaskIndex(),
                Optional.of(digest),
                checkpoint.taskApproval(),
                checkpoint.remediationAttempts(),
                false);
    }

    private static SddContracts.Checkpoint approveTasks(
            SddContracts.Definition definition, SddContracts.Checkpoint checkpoint, String digest) {
        boolean valid = checkpoint.phase() == SddContracts.Phase.TASK_APPROVAL
                && checkpoint
                        .specificationApproval()
                        .filter(definition.specificationDigest()::equals)
                        .isPresent()
                && definition.taskDigest().equals(digest);
        if (!valid) {
            throw new IllegalArgumentException("SDD task approval does not match current content");
        }
        return new SddContracts.Checkpoint(
                SddContracts.Phase.IMPLEMENT,
                0,
                checkpoint.specificationApproval(),
                Optional.of(digest),
                checkpoint.remediationAttempts(),
                false);
    }

    private record ExecutionKey(String jobId) {}
}
