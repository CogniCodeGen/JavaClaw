package com.javaclaw.desktop.settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJobCursor;
import com.javaclaw.extension.spi.ExtensionJobUnitState;
import com.javaclaw.protocol.InputJobRpcContracts;

/** Automation Job Presenter 与页面测试共用的纯内存 SDK 边界。 */
final class TestAutomationJobSettingsGateway implements AutomationJobSettingsGateway {
    private static final Instant NOW = DesktopTestFixtures.NOW;

    final Workspace workspace = DesktopTestFixtures.workspace();
    final List<ExtensionExecutionReceipt> firstPage = new ArrayList<>();
    final List<ExtensionExecutionReceipt> secondPage = new ArrayList<>();
    final Map<String, InputJobRpcContracts.JobReadResult> details = new HashMap<>();
    Optional<WorkspaceId> lastWorkspace = Optional.empty();
    Optional<String> lastExtension = Optional.empty();
    Set<ExecutionState> lastStates = Set.of();
    Optional<ExtensionJobCursor> lastCursor = Optional.empty();
    CommandOptions lastOptions;
    String lastAction = "";
    RuntimeException nextListFailure;
    RuntimeException nextMutationFailure;
    boolean manualReads;
    private final Map<String, CompletableFuture<InputJobRpcContracts.JobReadResult>> pendingReads = new HashMap<>();

    TestAutomationJobSettingsGateway() {
        ExtensionExecutionReceipt workflow =
                receipt("workflow-job", "com.javaclaw.workflow", ExecutionState.RUNNING, 3);
        ExtensionExecutionReceipt plan = receipt("plan-job", "com.javaclaw.plan", ExecutionState.PAUSED, 2);
        ExtensionExecutionReceipt knowledge =
                receipt("knowledge-job", "com.javaclaw.knowledge", ExecutionState.COMPLETED, 7);
        firstPage.addAll(List.of(workflow, plan));
        secondPage.add(knowledge);
        details.put(workflow.id(), detail(workflow, true));
        details.put(plan.id(), detail(plan, false));
        details.put(knowledge.id(), detail(knowledge, false));
    }

    @Override
    public CompletionStage<List<Workspace>> workspaces() {
        return CompletableFuture.completedFuture(List.of(workspace));
    }

    @Override
    public CompletionStage<InputJobRpcContracts.JobListResult> jobs(
            Optional<WorkspaceId> workspaceId,
            Optional<String> extensionId,
            Set<ExecutionState> states,
            Optional<ExtensionJobCursor> after,
            int limit) {
        lastWorkspace = workspaceId;
        lastExtension = extensionId;
        lastStates = Set.copyOf(states);
        lastCursor = after;
        if (nextListFailure != null) {
            RuntimeException failure = nextListFailure;
            nextListFailure = null;
            return CompletableFuture.failedFuture(failure);
        }
        if (after.isPresent()) {
            return CompletableFuture.completedFuture(
                    new InputJobRpcContracts.JobListResult(List.copyOf(secondPage), Optional.empty()));
        }
        if (firstPage.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new InputJobRpcContracts.JobListResult(List.of(), Optional.empty()));
        }
        ExtensionExecutionReceipt last = firstPage.getLast();
        ExtensionJobCursor next = new ExtensionJobCursor(last.updatedAt(), last.id());
        return CompletableFuture.completedFuture(
                new InputJobRpcContracts.JobListResult(List.copyOf(firstPage), Optional.of(next)));
    }

    @Override
    public CompletionStage<InputJobRpcContracts.JobReadResult> job(String jobId) {
        if (manualReads) {
            return pendingReads.computeIfAbsent(jobId, ignored -> new CompletableFuture<>());
        }
        return CompletableFuture.completedFuture(details.get(jobId));
    }

    @Override
    public CompletionStage<ExtensionExecutionReceipt> pause(ExtensionExecutionReceipt job, CommandOptions options) {
        return mutate("pause", job, options, ExecutionState.PAUSED);
    }

    @Override
    public CompletionStage<ExtensionExecutionReceipt> resume(ExtensionExecutionReceipt job, CommandOptions options) {
        return mutate("resume", job, options, ExecutionState.QUEUED);
    }

    @Override
    public CompletionStage<ExtensionExecutionReceipt> cancel(ExtensionExecutionReceipt job, CommandOptions options) {
        return mutate("cancel", job, options, ExecutionState.CANCELLED);
    }

    void completeRead(String jobId) {
        pendingReads.get(jobId).complete(details.get(jobId));
    }

    private CompletionStage<ExtensionExecutionReceipt> mutate(
            String action, ExtensionExecutionReceipt job, CommandOptions options, ExecutionState target) {
        lastAction = action;
        lastOptions = options;
        if (nextMutationFailure != null) {
            RuntimeException failure = nextMutationFailure;
            nextMutationFailure = null;
            return CompletableFuture.failedFuture(failure);
        }
        ExtensionExecutionReceipt updated = new ExtensionExecutionReceipt(
                job.id(),
                job.extensionId(),
                job.workspaceId(),
                job.jobType(),
                job.definitionId(),
                job.definitionRevision(),
                target,
                job.revision() + 1,
                Optional.empty(),
                job.createdAt(),
                job.updatedAt().plusSeconds(1));
        replace(updated);
        InputJobRpcContracts.JobReadResult current = details.get(job.id());
        details.put(job.id(), new InputJobRpcContracts.JobReadResult(updated, current.units()));
        return CompletableFuture.completedFuture(updated);
    }

    private void replace(ExtensionExecutionReceipt updated) {
        replace(firstPage, updated);
        replace(secondPage, updated);
    }

    private static void replace(List<ExtensionExecutionReceipt> jobs, ExtensionExecutionReceipt updated) {
        for (int index = 0; index < jobs.size(); index++) {
            if (jobs.get(index).id().equals(updated.id())) {
                jobs.set(index, updated);
            }
        }
    }

    private ExtensionExecutionReceipt receipt(String id, String extensionId, ExecutionState state, long revision) {
        return new ExtensionExecutionReceipt(
                id,
                new ExtensionId(extensionId),
                workspace.id(),
                "definition-execution",
                id + "-definition",
                4,
                state,
                revision,
                Optional.empty(),
                NOW,
                NOW.plusSeconds(revision));
    }

    private static InputJobRpcContracts.JobReadResult detail(ExtensionExecutionReceipt job, boolean activeUnit) {
        List<InputJobRpcContracts.JobUnitSummary> units = new ArrayList<>();
        units.add(new InputJobRpcContracts.JobUnitSummary(
                job.id(),
                1,
                "prepare",
                ExtensionJobUnitState.COMPLETED,
                Optional.of(TurnId.parse("7a6be7bf-11f4-4abc-bdd7-9df61fed6936")),
                Optional.of("effect-1"),
                Optional.empty(),
                NOW,
                Optional.of(NOW.plusSeconds(1))));
        if (activeUnit) {
            units.add(new InputJobRpcContracts.JobUnitSummary(
                    job.id(),
                    2,
                    "execute",
                    ExtensionJobUnitState.INTENT_RECORDED,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    NOW.plusSeconds(2),
                    Optional.empty()));
        }
        return new InputJobRpcContracts.JobReadResult(job, units);
    }
}
