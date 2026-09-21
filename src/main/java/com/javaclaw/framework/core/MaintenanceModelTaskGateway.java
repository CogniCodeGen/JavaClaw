package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Gives post-turn memory tasks a live, auditable owner without reopening the source turn. */
public final class MaintenanceModelTaskGateway implements ModelTaskGateway {
    private static final RunBudget MAINTENANCE_BUDGET = new RunBudget(java.time.Duration.ofMinutes(3),
            64_000, 8_000, 0, new java.math.BigDecimal("2.00"));
    private final ModelTaskGateway delegate;
    private final RunStore runs;
    private final Supplier<AgentClient> agents;
    public MaintenanceModelTaskGateway(ModelTaskGateway delegate, RunStore runs, Supplier<AgentClient> agents) {
        this.delegate = delegate; this.runs = runs; this.agents = agents;
    }
    @Override public CompletionStage<ModelTaskResult> execute(ModelTaskRequest request) {
        if (!request.purpose().startsWith("memory.")) return delegate.execute(request);
        StoredRun origin = runs.find(request.ownerRunId()).orElseThrow(() -> new IllegalStateException("memory source turn is unavailable"));
        if (origin.request().source().kind().equals("maintenance")) return delegate.execute(request);
        RunRequest source = origin.request();
        String id = UUID.nameUUIDFromBytes((source.scope() + ":memory-maintenance").getBytes(StandardCharsets.UTF_8)).toString();
        RunScope scope = new RunScope(source.scope().workspaceId(), source.scope().userId(), id);
        RunRequest maintenance = RunRequest.builder().agent(source.agent()).profile(source.profile())
                .scope(scope).source(new InvocationSource("maintenance", source.scope().sessionId()))
                .linkage(new RunLinkage(request.ownerRunId(), source.linkage().workflowRunId(), request.purpose()))
                .input(InputBlock.text(request.purpose())).permissionCeiling(PermissionSet.NONE)
                .budget(MAINTENANCE_BUDGET).attributes(java.util.Map.of(
                        "framework.maintenance", JsonNodeFactory.instance.booleanNode(true),
                        "framework.originTurnId", JsonNodeFactory.instance.textNode(request.ownerRunId().value()),
                        "framework.targetThreadId", JsonNodeFactory.instance.textNode(source.scope().sessionId())))
                .build();
        ManagedTurn turn = agents.get().beginTurn(maintenance);
        return turn.ready().thenCompose(ignored -> delegate.execute(new ModelTaskRequest(
                request.purpose(), request.tier(), request.input(), request.mediaInputs(), request.outputSchema(), turn.id(),
                request.budgetAccount(), request.timeout(), request.maxRetries(),
                () -> request.cancellation().cancelled() || turn.cancelled(), request.cacheAllowed())))
                .whenComplete((result, failure) -> {
                    try { if (failure == null) turn.complete(result.output()); else turn.fail(failure); }
                    finally { turn.close(); }
                });
    }
}
