package com.javaclaw.builtin.extensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.LoopContracts;
import com.javaclaw.builtin.contracts.OrchestrationContracts;
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

/** Loop Definition、真实证据规则与可恢复逐轮 Execution。 */
final class LoopExtension implements ExtensionBundle {
    private final ManagedDocumentResource<LoopContracts.Definition> documents = new ManagedDocumentResource<>(
            BuiltinExtensionIds.LOOP,
            "循环",
            LoopContracts.Definition.class,
            Set.of(ContributionKind.ORCHESTRATOR, ContributionKind.SCHEDULABLE_ACTION));
    private final AutomationExecutionResource<LoopContracts.Definition> executions = new AutomationExecutionResource<>(
            documents,
            "循环",
            LoopJobExecutor::new,
            ignored -> documents.payloads().encode(LoopJobExecutor.Checkpoint.initial()));
    private final LoopManagement management = new LoopManagement(documents);

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
                        "execution.confirm",
                        Set.of("execution/confirm", "execution/confirm-current", "execution/reject-current"),
                        this::confirm)),
                confirmationActions()));
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

    private ExtensionResponse confirm(ExtensionRequest request, ExtensionExecutionContext context) {
        LoopContracts.Confirmation confirmation =
                switch (request.operation()) {
                    case "execution/confirm" ->
                        documents.payloads().decode(request.payload(), LoopContracts.Confirmation.class);
                    case "execution/confirm-current" -> currentConfirmation(request, context, true);
                    case "execution/reject-current" -> currentConfirmation(request, context, false);
                    default -> throw new IllegalArgumentException("unknown Loop confirmation operation");
                };
        String key = request.idempotencyKey()
                .orElseThrow(() -> new IllegalArgumentException("execution/confirm requires idempotency key"));
        ExtensionJob current = context.jobs()
                .find(confirmation.jobId())
                .orElseThrow(() -> new IllegalArgumentException("Loop Execution does not exist"));
        requireConfirmationTarget(request, confirmation, current);
        ExtensionJobMutation mutation = new ExtensionJobMutation(key, request.expectedRevision());
        ExtensionJob updated = confirmation.confirmed()
                ? continueConfirmed(current, confirmation, mutation, context)
                : context.jobs().cancel(current.id(), mutation);
        return new ExtensionResponse(
                documents.payloads().encode(ExtensionExecutionReceipt.from(updated)), updated.revision());
    }

    private LoopContracts.Confirmation currentConfirmation(
            ExtensionRequest request, ExtensionExecutionContext context, boolean confirmed) {
        ExecutionKey input = documents.payloads().decode(request.payload(), ExecutionKey.class);
        ExtensionJob current = context.jobs()
                .find(input.jobId())
                .orElseThrow(() -> new IllegalArgumentException("Loop Execution does not exist"));
        OrchestrationContracts.ExecutionCheckpoint shared =
                documents.payloads().decode(current.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
        LoopJobExecutor.Checkpoint checkpoint =
                documents.payloads().decode(shared.domain(), LoopJobExecutor.Checkpoint.class);
        int iteration = checkpoint
                .awaitingConfirmation()
                .orElseThrow(() -> new IllegalArgumentException("Loop Execution is not awaiting confirmation"));
        return new LoopContracts.Confirmation(current.id(), iteration, confirmed);
    }

    private static List<ViewAction> confirmationActions() {
        ExpectedRevisionBinding revision = new ExpectedRevisionBinding.RowField("revision");
        return List.of(
                new ViewAction("确认本轮完成", "execution/confirm-current", Map.of(), Map.of("jobId", "id"), revision, false),
                new ViewAction("拒绝并终止", "execution/reject-current", Map.of(), Map.of("jobId", "id"), revision, true));
    }

    private ExtensionJob continueConfirmed(
            ExtensionJob current,
            LoopContracts.Confirmation confirmation,
            ExtensionJobMutation mutation,
            ExtensionExecutionContext context) {
        OrchestrationContracts.ExecutionCheckpoint shared =
                documents.payloads().decode(current.checkpoint(), OrchestrationContracts.ExecutionCheckpoint.class);
        LoopJobExecutor.Checkpoint checkpoint =
                documents.payloads().decode(shared.domain(), LoopJobExecutor.Checkpoint.class);
        if (checkpoint.awaitingConfirmation().orElse(-1) != confirmation.iteration()) {
            throw new IllegalArgumentException("Loop 等待轮次与确认不一致");
        }
        LoopJobExecutor.Checkpoint continued = checkpoint.afterConfirmation();
        return context.jobs()
                .continueWaiting(
                        current.id(),
                        ExecutionState.WAITING_INPUT,
                        documents
                                .payloads()
                                .encode(new OrchestrationContracts.ExecutionCheckpoint(
                                        documents.payloads().encode(continued), shared.consumption())),
                        mutation);
    }

    private void requireConfirmationTarget(
            ExtensionRequest request, LoopContracts.Confirmation confirmation, ExtensionJob current) {
        if (!current.extensionId().equals(documents.extensionId())
                || !current.workspaceId().equals(request.workspaceId())
                || !AutomationExecutionResource.JOB_TYPE.equals(current.jobType())
                || current.state() != ExecutionState.WAITING_INPUT) {
            throw new IllegalArgumentException("Job 不是当前 Workspace 中等待确认的 Loop Execution");
        }
        OrchestrationContracts.FrozenExecution frozen =
                documents.payloads().decode(current.frozenInput(), OrchestrationContracts.FrozenExecution.class);
        LoopContracts.Definition definition =
                documents.payloads().decode(frozen.definition(), LoopContracts.Definition.class);
        if (definition.verificationRule().kind() != LoopContracts.VerificationKind.USER_CONFIRMATION
                || confirmation.iteration() > definition.maximumIterations()) {
            throw new IllegalArgumentException("Loop Definition 不接受该人工确认");
        }
    }

    private record ExecutionKey(String jobId) {}
}
