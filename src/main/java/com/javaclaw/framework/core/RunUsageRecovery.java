package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.ExecutionPlanStore;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads complete budget families, including completed children, without rerunning usage observers. */
final class RunUsageRecovery {
    private final RunStore runs;
    private final ExecutionPlanStore plans;
    private final ObjectMapper json;
    private final RunUsageLedger usage;
    RunUsageRecovery(RunStore runs, ExecutionPlanStore plans, ObjectMapper json, RunUsageLedger usage) {
        this.runs = runs; this.plans = plans; this.json = json; this.usage = usage;
    }
    static RunId budgetParent(com.javaclaw.framework.api.RunRequest request) {
        return request.source().kind().equals("maintenance") ? null : request.linkage().parentRunId();
    }
    void restore(RunId id) {
        if (id == null || usage.contains(id)) return;
        StoredRun root = runs.find(id).orElseThrow(() -> new IllegalStateException("parent turn does not exist: " + id));
        Set<RunId> ancestors = new HashSet<>();
        while (root.request().linkage().parentRunId() != null) {
            if (!ancestors.add(root.snapshot().id())) throw new IllegalStateException("cyclic parent run linkage");
            RunId parent = root.request().linkage().parentRunId();
            root = runs.find(parent).orElseThrow(() -> new IllegalStateException("parent turn does not exist: " + parent));
        }
        restoreTree(root, new HashSet<>());
        if (!usage.contains(id)) throw new IllegalStateException("usage parent linkage crosses workspace or user scope");
    }
    private void restoreTree(StoredRun run, Set<RunId> visited) {
        RunId id = run.snapshot().id();
        if (!visited.add(id)) throw new IllegalStateException("cyclic child run linkage");
        if (!usage.contains(id)) {
            RunBudget budget = run.request().budget();
            try {
                JsonNode value = plans.find(run.snapshot().executionPlanId()).orElse(null);
                if (value != null) budget = json.treeToValue(value, ExecutionPlanDescriptor.class).budget();
            } catch (Exception ignored) { /* Plan availability is handled separately by execution recovery. */ }
            usage.open(id, budget, run.request().scope(), budgetParent(run.request()));
            RunUsageLedger.UsageSnapshot totals = totals(runs.eventsAfter(id, 0));
            usage.restore(id, totals.inputTokens(), totals.outputTokens(), totals.cost());
            if (run.snapshot().state().terminal()) usage.close(id);
        }
        runs.childRuns(id).forEach(child -> restoreTree(child, visited));
    }

    static RunUsageLedger.UsageSnapshot totals(List<RunEventEnvelope> events) {
        Map<String, String> kinds = new HashMap<>();
        Map<String, JsonNode> steps = new HashMap<>();
        long firstModel = Long.MAX_VALUE, firstTask = Long.MAX_VALUE;
        for (var event : events) {
            if (!event.type().startsWith("core.step.")) continue;
            String id = event.payload().path("stepId").asText();
            if (event.type().equals("core.step.started")) {
                String kind = event.payload().path("kind").asText();
                kinds.put(id, kind);
                if (kind.equals("MODEL")) firstModel = Math.min(firstModel, event.sequence());
                if (kind.equals("MODEL_TASK")) firstTask = Math.min(firstTask, event.sequence());
            } else if (event.payload().has("usage")) steps.put(id, event.payload().path("usage"));
        }
        long input = 0, output = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (var entry : steps.entrySet()) {
            String kind = kinds.get(entry.getKey());
            if (!"MODEL".equals(kind) && !"MODEL_TASK".equals(kind)) continue;
            JsonNode value = entry.getValue();
            input = Math.addExact(input, Math.max(0, value.path("inputTokens").asLong()));
            output = Math.addExact(output, Math.max(0, value.path("outputTokens").asLong()));
            cost = cost.add(value.path("estimatedCostCny").decimalValue().max(BigDecimal.ZERO));
        }
        boolean primaryUsage = events.stream().anyMatch(event -> event.type().equals("core.model.usage"));
        boolean taskUsage = events.stream().anyMatch(event -> event.type().equals("core.model_task.usage"));
        Set<Long> seen = new HashSet<>();
        for (var event : events) {
            boolean legacyModel = event.sequence() < firstModel && event.type().equals(primaryUsage ? "core.model.usage" : "core.model.completed");
            boolean legacyTask = event.sequence() < firstTask && event.type().equals(taskUsage ? "core.model_task.usage" : "core.model_task.completed");
            if ((!legacyModel && !legacyTask) || !seen.add(event.sequence())) continue;
            input = Math.addExact(input, Math.max(0, event.payload().path("inputTokens").asLong()));
            output = Math.addExact(output, Math.max(0, event.payload().path("outputTokens").asLong()));
            cost = cost.add(event.payload().path("estimatedCostCny").decimalValue().max(BigDecimal.ZERO));
        }
        return new RunUsageLedger.UsageSnapshot(input, output, cost);
    }
}
