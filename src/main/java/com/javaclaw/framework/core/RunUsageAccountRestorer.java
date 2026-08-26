package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ExecutionPlanStore;
import com.javaclaw.framework.spi.RunStore;

import java.util.Objects;

/** Reopens an evicted usage account from the durable Run and execution-plan fact sources. */
public final class RunUsageAccountRestorer {
    private final RunStore runs;
    private final ExecutionPlanStore plans;
    private final RunUsageLedger ledger;
    private final ObjectMapper json;

    public RunUsageAccountRestorer(
            RunStore runs, ExecutionPlanStore plans,
            RunUsageLedger ledger, ObjectMapper json) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.json = Objects.requireNonNull(json, "json");
    }

    public RunUsageLedger.UsageSnapshot ensureAccount(RunId runId) {
        Objects.requireNonNull(runId, "runId");
        RunUsageLedger.UsageSnapshot retained = ledger.retainAccount(runId);
        if (retained != null) return retained;
        var stored = runs.find(runId).orElseThrow(() ->
                new IllegalStateException("owner run not found for usage restoration: " + runId));
        var planJson = plans.find(stored.snapshot().executionPlanId()).orElseThrow(() ->
                new IllegalStateException("execution plan not found for usage restoration: "
                        + stored.snapshot().executionPlanId()));
        ExecutionPlanDescriptor descriptor;
        try {
            descriptor = json.treeToValue(planJson, ExecutionPlanDescriptor.class);
        } catch (Exception failure) {
            throw new IllegalStateException("invalid persisted execution plan for " + runId, failure);
        }
        DurableUsageHistory.Snapshot history = DurableUsageHistory.from(runs.eventsAfter(runId, 0));
        return ledger.restoreAccount(runId, descriptor.budget(), stored.request().scope(),
                history, stored.snapshot().state().terminal());
    }
}
