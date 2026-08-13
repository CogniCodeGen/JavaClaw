package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.spi.*;

import java.util.Set;

/** Writes auxiliary model activity into the owning run's durable event/outbox stream. */
public final class RunEventModelTaskAuditSink implements ModelTaskAuditSink {
    private final RunStore runs;

    public RunEventModelTaskAuditSink(RunStore runs) {
        this.runs = runs;
    }

    @Override
    public void started(ModelTaskRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("purpose", request.purpose());
        payload.put("tier", request.tier().name());
        append(request, "core.model_task.started", payload);
    }

    @Override
    public void usage(
            ModelTaskRequest request,
            String model,
            int attempt,
            long inputTokens,
            long outputTokens,
            java.math.BigDecimal estimatedCostCny) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("purpose", request.purpose());
        payload.put("tier", request.tier().name());
        payload.put("model", model);
        payload.put("attempt", attempt);
        payload.put("inputTokens", inputTokens);
        payload.put("outputTokens", outputTokens);
        payload.put("estimatedCostCny", estimatedCostCny);
        append(request, "core.model_task.usage", payload);
    }

    @Override
    public void completed(ModelTaskRequest request, ModelTaskResult result) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("purpose", request.purpose());
        payload.put("tier", request.tier().name());
        payload.put("model", result.model());
        payload.put("inputTokens", result.inputTokens());
        payload.put("outputTokens", result.outputTokens());
        payload.put("cacheHit", result.cacheHit());
        append(request, "core.model_task.completed", payload);
    }

    @Override
    public void failed(ModelTaskRequest request, Throwable failure) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("purpose", request.purpose());
        payload.put("tier", request.tier().name());
        payload.put("errorType", failure.getClass().getName());
        payload.put("message", failure.getMessage() == null ? "" : failure.getMessage());
        append(request, "core.model_task.failed", payload);
    }

    private void append(ModelTaskRequest request, String type, ObjectNode payload) {
        var stored = runs.find(request.ownerRunId());
        if (stored.isEmpty() || stored.get().snapshot().state().terminal()) return;
        var state = stored.get().snapshot().state();
        runs.append(request.ownerRunId(), Set.of(state), state,
                new RunEventDraft(type, 1, "framework.springai",
                        stored.get().request().linkage().correlationId(), null, payload),
                null, null);
    }
}
