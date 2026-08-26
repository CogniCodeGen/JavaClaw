package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.spi.*;
import com.javaclaw.framework.api.ModelTokenUsage;

/** Writes auxiliary model activity into the owning run's durable event/outbox stream. */
public final class RunEventModelTaskAuditSink implements ModelTaskAuditSink {
    private final RunStore runs;
    private final CommittedRunEventWriter events;

    public RunEventModelTaskAuditSink(RunStore runs, RunEventRelay events) {
        this.runs = java.util.Objects.requireNonNull(runs, "runs");
        this.events = new CommittedRunEventWriter(
                runs, java.util.Objects.requireNonNull(events, "events"));
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
        usage(request, model, attempt,
                new ModelTokenUsage(inputTokens, outputTokens), estimatedCostCny);
    }

    @Override
    public void usage(ModelTaskRequest request, String model, int attempt,
                      ModelTokenUsage usage, java.math.BigDecimal estimatedCostCny) {
        commitUsage(request, "legacy:" + java.util.UUID.randomUUID(), model, attempt,
                usage, estimatedCostCny);
    }

    @Override
    public boolean commitUsage(
            ModelTaskRequest request,
            String modelCallId,
            String model,
            int attempt,
            ModelTokenUsage usage,
            java.math.BigDecimal estimatedCostCny) {
        return commitUsage(request, modelCallId, model, attempt, java.time.Instant.now(),
                usage, usage.inputTokens(), estimatedCostCny);
    }

    @Override
    public boolean commitUsage(
            ModelTaskRequest request,
            String modelCallId,
            String model,
            int attempt,
            java.time.Instant occurredAt,
            ModelTokenUsage usage,
            long pricingInputTokens,
            java.math.BigDecimal estimatedCostCny) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("purpose", request.purpose());
        payload.put("tier", request.tier().name());
        payload.put("attribution", request.attribution().name());
        payload.put("modelCallId", modelCallId);
        payload.put("model", model);
        payload.put("attempt", attempt);
        payload.put("occurredAtEpochMillis", occurredAt.toEpochMilli());
        payload.put("inputTokens", usage.inputTokens());
        payload.put("pricingInputTokens", Math.max(0, pricingInputTokens));
        payload.put("cacheReadInputTokens", usage.cacheReadInputTokens());
        payload.put("cacheWriteInputTokens", usage.cacheWriteInputTokens());
        payload.put("outputTokens", usage.outputTokens());
        payload.put("reasoningTokens", usage.reasoningTokens());
        payload.put("modelCalls", usage.modelCalls());
        payload.put("estimatedCostCny", estimatedCostCny);
        return append(request, "core.model_task.usage", 3, payload, true);
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

    private boolean append(ModelTaskRequest request, String type, ObjectNode payload) {
        return append(request, type, 1, payload, false);
    }

    private boolean append(
            ModelTaskRequest request, String type, int schemaVersion, ObjectNode payload) {
        return append(request, type, schemaVersion, payload, false);
    }

    private boolean append(
            ModelTaskRequest request, String type, int schemaVersion,
            ObjectNode payload, boolean terminalSafeUsage) {
        var stored = runs.find(request.ownerRunId());
        if (stored.isEmpty()) return false;
        if (stored.get().snapshot().state().terminal()
                && !terminalSafeUsage
                && request.attribution() != ModelTaskAttribution.BACKGROUND) {
            return false;
        }
        payload.put("attribution", request.attribution().name());
        return events.append(request.ownerRunId(),
                new RunEventDraft(type, schemaVersion, "framework.springai",
                        stored.get().request().linkage().correlationId(), null, payload))
                .isPresent();
    }
}
