package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.BrowserNavigationNoOpProvider;
import com.javaclaw.framework.spi.BrowserReceiptProof;
import com.javaclaw.framework.spi.InteractionStageContext;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry;
import java.util.List;
import java.util.Optional;

/** A completed navigation permits only a later live-state read, never replay. */
final class BrowserNavigationNoOpAdmission {
    private BrowserNavigationNoOpAdmission() { }

    static Optional<BrowserNavigationNoOpProvider.Prepared> prepare(RunStore runs, ObjectMapper json,
            ToolInvocationRequest request, PendingEffectObservationRequiredException blocked) {
        if (runs == null || !InteractionExecutionPolicy.isInteraction(request.runRequest())
                || !request.tool().descriptor().name().equals("web_navigate")
                || !SpringAiAnnotatedToolRegistry.isExactHostTool(request.tool())
                || blocked.reason() != PendingEffectObservationRequiredException.Reason.EFFECT_ALREADY_ATTEMPTED
                || blocked.status() != com.javaclaw.framework.spi.EffectReceiptV1.Status.ACCEPTED
                || blocked.invocationId().equals(request.context().invocationId())) return Optional.empty();
        String destination = BrowserReceiptProof.canonicalUrl(request.arguments().path("url").asText(""));
        if (destination.isBlank()) return Optional.empty();
        var current = runs.find(request.context().runId()).orElse(null);
        RunId oldRunId = blocked.sourceRunId() == null || blocked.sourceRunId().isBlank()
                ? request.context().runId() : new RunId(blocked.sourceRunId());
        var old = runs.find(oldRunId).orElse(null);
        if (current == null || old == null || !InteractionExecutionPolicy.isInteraction(current.request())
                || !InteractionExecutionPolicy.isInteraction(old.request())
                || !current.request().scope().equals(request.runRequest().scope())
                || !old.request().scope().equals(current.request().scope())
                || !runs.readable(current.request().scope()) || !runs.readable(old.request().scope())) return Optional.empty();
        var oldReceipt = completedNavigation(runs.eventsAfter(oldRunId, 0), oldRunId,
                blocked.invocationId(), destination).orElse(null);
        if (oldReceipt == null) return Optional.empty();
        List<RunEventEnvelope> events = runs.eventsAfter(request.context().runId(), 0).stream()
                .filter(event -> event.runId().equals(request.context().runId().value())
                        && event.producer().equals("framework.core")).toList();
        // findFirst in the effect map may identify an older attempt; every newer own no-op
        // must consume a later observation as well, rather than reusing one old body read.
        var latestNavigation = completedNavigation(events, request.context().runId(), null, destination).orElse(null);
        long afterNavigation = latestNavigation == null ? 0 : latestNavigation.sequence();
        long afterCapturedAt = Math.max(oldReceipt.timestamp().toEpochMilli(), latestNavigation == null
                ? 0 : latestNavigation.timestamp().toEpochMilli());
        var contract = events.stream().filter(event -> event.schemaVersion() == 3
                && java.util.Set.of("core.task.contract", "core.task.contract_revised").contains(event.type()))
                .max(java.util.Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (contract == null) return Optional.empty();
        long selected = events.stream().filter(event -> event.type().equals(InteractionExecutionPolicy.MODE_SELECTED_EVENT))
                .mapToLong(RunEventEnvelope::sequence).max().orElse(0);
        long freshnessBoundary = Math.max(Math.max(selected, contract.sequence()), afterNavigation);
        String contractHash = InteractionStageContext.sha256(contract.payload().toString());
        // Only this Run's successful full-body read may bind the prepared actual Page identity.
        for (var event : events.stream().filter(value -> value.sequence() > freshnessBoundary
                && value.schemaVersion() == 1 && value.type().equals("core.tool.receipt"))
                .sorted(java.util.Comparator.comparingLong(RunEventEnvelope::sequence).reversed()).toList()) {
            JsonNode receipt = event.payload();
            String tool = receipt.path("tool").asText();
            String invocation = receipt.path("invocationId").asText();
            if (!java.util.Set.of("web_snapshot", "web_get_text").contains(tool)
                    || !receipt.path("operation").asText().equals("observe")
                    || !receipt.path("status").asText().equals("OBSERVED")
                    || !receipt.path("evidenceRef").asText().equals("core.tool.completed:"
                            + request.context().runId().value() + ":" + invocation)
                    || !BrowserReceiptProof.urlMatches(destination, receipt.path("metadata"))
                    || !successfulHostCall(events, event, tool, invocation,
                            freshnessBoundary)) continue;
            try {
                String encoded = receipt.path("metadata").path("interactionStage").asText("");
                if (encoded.isBlank() || encoded.length() > 32_000) continue;
                JsonNode identity = json.readTree(encoded);
                if (identity == null || !identity.isObject() || identity.path("schemaVersion").asInt() != 1
                        || !identity.path("kind").asText().equals("observation")
                        || !identity.path("mode").asText().equals("BROWSER")
                        || !identity.path("complete").isBoolean() || !identity.path("complete").booleanValue()
                        || !identity.path("contractSequence").isIntegralNumber()
                        || identity.path("contractSequence").asLong() != contract.sequence()
                        || !identity.path("contractSha256").asText().equals(contractHash)
                        || !identity.path("urlSha256").asText().equals(BrowserReceiptProof.urlDigest(destination))
                        || !identity.path("capturedAtMillis").isIntegralNumber() || identity.path("capturedAtMillis").asLong() <= 0
                        || identity.path("capturedAtMillis").asLong() < afterCapturedAt
                        || identity.path("capturedAtMillis").asLong() > event.timestamp().toEpochMilli()
                        || !identity.path("generation").isIntegralNumber() || identity.path("generation").asLong() < 1
                        || java.util.List.of("runtimeId", "contextId", "surfaceId", "documentId", "observationId")
                                .stream().anyMatch(key -> !identity.path(key).isTextual() || identity.path(key).asText().isBlank())) continue;
                return SpringAiAnnotatedToolRegistry.prepareBrowserNavigationNoOp(
                        request.tool(), request.arguments(), request.context(), identity.deepCopy());
            } catch (Exception unavailable) {
                // Missing or stale state cannot authorize the ordinary navigation callback.
                request.context().cancellation().throwIfCancelled();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<RunEventEnvelope> completedNavigation(List<RunEventEnvelope> history, RunId run,
            String invocation, String destination) {
        List<RunEventEnvelope> events = history.stream().filter(event -> event.runId().equals(run.value())
                && event.producer().equals("framework.core")).toList();
        for (var receipt : events.stream().sorted(java.util.Comparator.comparingLong(RunEventEnvelope::sequence)
                .reversed()).toList()) {
            JsonNode value = receipt.payload();
            String candidateInvocation = value.path("invocationId").asText();
            if (receipt.schemaVersion() != 1 || !receipt.type().equals("core.tool.receipt")
                    || !value.path("tool").asText().equals("web_navigate")
                    || candidateInvocation.isBlank() || invocation != null && !candidateInvocation.equals(invocation)
                    || !value.path("operation").asText().equals("navigate")
                    || !value.path("status").asText().equals("ACCEPTED")
                    || !completedDelivery(value.path("metadata"))
                    || !value.path("evidenceRef").asText().equals("core.tool.completed:" + run.value() + ":" + candidateInvocation)
                    || !BrowserReceiptProof.urlMatches(destination, value.path("metadata"))
                    || !successfulHostCall(events, receipt, "web_navigate", candidateInvocation, 0)) continue;
            boolean exact = events.stream().anyMatch(event -> event.type().equals("core.tool.started")
                    && event.schemaVersion() == 1 && event.sequence() < receipt.sequence()
                    && event.payload().path("invocationId").asText().equals(candidateInvocation)
                    && event.payload().path("trustedBrowserNavigation").asBoolean(false)
                    && event.payload().path("effectPolicy").asText().equals("LEGACY")
                    && !event.payload().path("idempotent").asBoolean(true)
                    && (!value.path("metadata").path("delivery").asText().equals("NOT_SENT")
                        || event.payload().path("readOnlyNavigationNoOp").asBoolean(false))
                    && destination.equals(BrowserReceiptProof.canonicalUrl(
                            event.payload().path("arguments").path("url").asText())));
            if (exact) return Optional.of(receipt);
        }
        return Optional.empty();
    }

    private static boolean completedDelivery(JsonNode metadata) {
        String delivery = metadata.path("delivery").asText();
        return java.util.Set.of("", "SENT").contains(delivery)
                || delivery.equals("NOT_SENT") && metadata.path("reusedExisting").asText().equals("true")
                    && metadata.path("dispatchAttempted").asText().equals("false")
                    && metadata.path("effect").asText().equals("NONE");
    }

    private static boolean successfulHostCall(List<RunEventEnvelope> events, RunEventEnvelope receipt,
            String tool, String invocation, long afterSequence) {
        var start = events.stream().filter(event -> event.schemaVersion() == 1 && event.type().equals("core.tool.started")
                && event.sequence() > afterSequence && event.sequence() < receipt.sequence()
                && event.payload().path("tool").asText().equals(tool)
                && event.payload().path("invocationId").asText().equals(invocation)
                && event.payload().path("trustedDesktopTool").asBoolean(false))
                .max(java.util.Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        return start != null && events.stream().anyMatch(event -> event.schemaVersion() == 2
                && event.type().equals("core.tool.completed") && event.sequence() > start.sequence()
                && event.sequence() < receipt.sequence() && event.payload().path("tool").asText().equals(tool)
                && event.payload().path("invocationId").asText().equals(invocation)
                && event.payload().path("status").asText().equals("SUCCEEDED"));
    }
}
