package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.*;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.StoredRun;
import java.util.*;

/** Bounded host result assembled from the original journal, never from a child's success claim. */
public final class InteractionResultProjector {
    private InteractionResultProjector() {}

    public static InteractionResult project(RunStore runs, ObjectMapper json, StoredRun child) {
        var events = runs.eventsAfter(child.snapshot().id(), 0);
        var task = child.request().attributes().get(InteractionExecutionPolicy.TASK_ATTRIBUTE);
        var contract = TaskResultEvaluator.latestContractV3(events, json).orElse(null);
        var acceptanceEvents = TaskEvidenceCollector.collect(runs, child.snapshot().id());
        TaskResult result = TaskResultEvaluator.evaluateV3(contract,
                acceptanceEvents,
                child.snapshot().error() == null ? "" : child.snapshot().error(), TrustedCapabilityRegistry.builtins());
        var selectedEvidence = TaskResultEvaluator.criterionEvidenceV3(contract, acceptanceEvents,
                child.snapshot().error(), TrustedCapabilityRegistry.builtins());
        List<String> satisfied = new ArrayList<>(), unmet = new ArrayList<>();
        var data = JsonNodeFactory.instance.objectNode();
        data.put("taskOutcome", result.outcome().name());
        var criterionEvidence = data.putObject("criterionEvidence");
        if (contract != null) for (var criterion : contract.criteria()) {
            boolean accepted = selectedEvidence.containsKey(criterion.id());
            (accepted ? satisfied : unmet).add(criterion.id());
            var item = criterionEvidence.putObject(criterion.id()).put("satisfied", accepted)
                    .put("mode", criterion.capabilityId().startsWith("browser.") ? "BROWSER" : "DESKTOP")
                    .put("target", criterion.target().substring(0, Math.min(128, criterion.target().length())))
                    .put("targetTruncated", criterion.target().length() > 128);
            var evidence = item.putArray("evidenceRefs");
            String ref = selectedEvidence.getOrDefault(criterion.id(), "");
            if (!ref.isBlank() && ref.length() <= 512) evidence.add(ref);
        }
        List<JsonNode> unknown = new ArrayList<>();
        for (StoredRun source : CrossModeBusinessFence.authorizedSources(runs, json, child)) {
            unknown.addAll(unknownEffects(runs.eventsAfter(source.snapshot().id(), 0), source.snapshot().id()));
        }
        boolean deliveryUnknown = !unknown.isEmpty();
        var pendingBusiness = CrossModeBusinessFence.pending(runs, json, child);
        boolean crossedBusiness = pendingBusiness.stream().anyMatch(CrossModeBusinessFence.Pending::crossed);
        var reportedBusiness = new LinkedHashMap<String, CrossModeBusinessFence.Pending>();
        for (var pending : pendingBusiness) if (pending.crossed())
            reportedBusiness.put(pending.sourceRunId().value() + ":" + pending.start().payload().path("invocationId").asText(), pending);
        if (child.snapshot().state() != RunState.COMPLETED || result.outcome() != TaskOutcome.VERIFIED_COMPLETE) {
            // A failed/cancelled/incomplete task must disclose its last unproved delivered input per backend.
            var latest = new EnumMap<InteractionMode, CrossModeBusinessFence.Pending>(InteractionMode.class);
            for (var pending : pendingBusiness) latest.put(pending.mode(), pending);
            for (var pending : latest.values()) reportedBusiness.put(pending.sourceRunId().value() + ":"
                    + pending.start().payload().path("invocationId").asText(), pending);
        }
        for (var pending : reportedBusiness.values()) unknown.add(pending.toJson());
        data.put("pendingBusinessEffectCount", pendingBusiness.size());
        var output = child.snapshot().output();
        String summary = output == null ? "" : output.path("text").asText(output.path("summary").asText(""));
        boolean hasLargeResult = summary.length() > 600
                || output != null && output.path("data").isObject() && output.path("data").toString().length() > 4_000;
        if (summary.length() > 600) summary = summary.substring(0, 600);
        if (output != null && output.path("data").isObject() && output.path("data").toString().length() <= 4_000)
            data.set("business", output.path("data"));
        List<String> refs = new ArrayList<>();
        if (hasLargeResult) refs.add("run:" + child.snapshot().id().value() + ":output");
        if (output != null) output.path("resultRefs").forEach(ref -> {
            if (ref.isTextual() && ref.asText().length() <= 512 && refs.size() < 16 && !refs.contains(ref.asText())) refs.add(ref.asText());
        });
        HostStop stop = hostStop(events, child.snapshot().id(), child.snapshot().state());
        String error = child.snapshot().state() == RunState.CANCELLED ? "CANCELLED"
                : result.outcome() == TaskOutcome.VERIFIED_COMPLETE ? "" : "TASK_UNVERIFIED";
        if (!stop.code().isBlank()) error = stop.code();
        if (!stop.message().isBlank()) {
            data.put("stopReason", stop.message());
            data.put("hostFailureEvidenceRef", stop.evidenceRef());
            if (summary.isBlank()) summary = stop.message();
        }
        if (crossedBusiness) error = "CROSS_MODE_BUSINESS_UNVERIFIED";
        if (deliveryUnknown) error = "EFFECT_UNKNOWN";
        if (unknown.size() > 32 || unknown.stream().mapToInt(value -> value.toString().length()).sum() > 8_000) {
            data.put("unknownEffectsTruncated", true).put("unknownEffectCount", unknown.size());
            List<JsonNode> bounded = new ArrayList<>();
            int size = 0;
            for (JsonNode effect : unknown) {
                if (bounded.size() == 32 || size + effect.toString().length() > 8_000) break;
                bounded.add(effect); size += effect.toString().length();
            }
            unknown = bounded;
        }
        appendExecutionProgress(data, events, child.snapshot().id());
        return new InteractionResult(1, task.path("taskId").asText(), task.path("revision").asLong(1),
                child.snapshot().id().value(), child.snapshot().state(), satisfied, unmet, summary, error,
                result.evidenceRefs().stream().filter(ref -> ref.length() <= 512).distinct().limit(32).toList(), unknown, data, refs);
    }

    /** A terminal failure does not erase matched criteria or invent a permission failure. */
    private static HostStop hostStop(List<RunEventEnvelope> events, RunId owner, RunState state) {
        String expected = switch (state) {
            case FAILED -> "core.run.failed";
            case CANCELLED -> "core.run.cancelled";
            case PAUSED -> "core.run.paused";
            default -> "";
        };
        if (expected.isBlank()) return new HostStop("", "", "");
        var terminal = events.stream().filter(event -> event.runId().equals(owner.value())
                && event.schemaVersion() == 1 && event.producer().equals("framework.core")
                && event.type().equals(expected))
                .max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (terminal == null) return new HostStop("", "", "");
        var payload = terminal.payload();
        String reason = payload.path("reason").asText("");
        String message = payload.path("message").asText("");
        String errorType = payload.path("errorType").asText("");
        String code = "";
        if (terminal.type().equals("core.run.failed")
                && (errorType.equals("com.javaclaw.framework.springai.ToolSchemaBudgetExceededException")
                    || errorType.equals("com.javaclaw.framework.springai.ToolCountBudgetExceededException")
                    || errorType.equals("java.lang.IllegalStateException")
                        && (message.startsWith("planned tool count exceeds maxTools=")
                            || message.equals("planned and activated tools exceed on-demand selection limit")
                            || message.equals("harness decision callback exceeds model context limits"))))
            code = "INTERACTION_TOOL_SELECTION_BUDGET";
        else if (!payload.path("budgetKind").asText("").isBlank()) code = "BUDGET_EXHAUSTED";
        else {
            for (String candidate : List.of(payload.path("errorCode").asText(""),
                    payload.path("code").asText(""), reason)) {
                var match = java.util.regex.Pattern.compile("^([A-Z][A-Z0-9_]{2,127})(?::|$)").matcher(candidate);
                if (match.find()) { code = match.group(1); break; }
            }
        }
        if (code.isBlank()) code = terminal.type().equals("core.run.failed") ? "INTERACTION_EXECUTION_FAILED"
                : terminal.type().equals("core.run.cancelled") ? "CANCELLED" : "INTERACTION_PAUSED";
        String display = boundedHostText(message.isBlank() ? reason : message, 512);
        if (display.isBlank()) display = code;
        return new HostStop(code, display, terminal.type() + ":" + owner.value() + ":" + terminal.sequence());
    }

    /** Successful preparatory calls are progress, not substitutes for unmet frozen criteria. */
    private static void appendExecutionProgress(com.fasterxml.jackson.databind.node.ObjectNode data,
            List<RunEventEnvelope> events, RunId owner) {
        Map<String, List<RunEventEnvelope>> calls = new LinkedHashMap<>();
        for (var event : events) if (event.runId().equals(owner.value())
                && event.producer().equals("framework.core")
                && Set.of("core.tool.started", "core.tool.completed", "core.tool.receipt").contains(event.type()))
            calls.computeIfAbsent(event.payload().path("invocationId").asText(), ignored -> new ArrayList<>()).add(event);
        var progress = JsonNodeFactory.instance.arrayNode();
        for (var entry : calls.entrySet()) {
            var triple = entry.getValue();
            if (triple.size() != 3) continue;
            var start = triple.get(0); var complete = triple.get(1); var receipt = triple.get(2);
            var value = receipt.payload();
            String tool = start.payload().path("tool").asText();
            String ref = value.path("evidenceRef").asText();
            if (!start.type().equals("core.tool.started") || start.schemaVersion() != 1
                    || !start.payload().path("trustedDesktopTool").isBoolean()
                    || !start.payload().path("trustedDesktopTool").booleanValue()
                    || !com.javaclaw.agent.ToolRiskRegistry.isKnownHostTool(tool)
                    || !tool.startsWith("desktop_session_") && !tool.startsWith("web_") && !tool.startsWith("site_")
                    || !complete.type().equals("core.tool.completed") || complete.schemaVersion() != 2
                    || !complete.payload().path("status").asText().equals("SUCCEEDED")
                    || !complete.payload().path("tool").asText().equals(tool)
                    || !receipt.type().equals("core.tool.receipt") || receipt.schemaVersion() != 1
                    || !value.path("tool").asText().equals(tool)
                    || !Set.of("ACCEPTED", "OBSERVED", "VERIFIED").contains(value.path("status").asText())
                    || !ref.equals("core.tool.completed:" + owner.value() + ":" + entry.getKey())
                    || ref.length() > 512 || tool.length() > 128) continue;
            var item = JsonNodeFactory.instance.objectNode().put("tool", tool)
                    .put("operation", boundedHostText(value.path("operation").asText(), 64))
                    .put("status", value.path("status").asText())
                    .put("target", boundedHostText(value.path("target").asText(), 96))
                    .put("evidenceRef", ref);
            progress.add(item);
            while (progress.size() > 4 || progress.toString().length() > 2_000) progress.remove(0);
        }
        if (progress.isEmpty()) return;
        data.put("executionProgressIsCriterionAcceptance", false);
        data.set("executedToolReceipts", progress);
        while (!progress.isEmpty() && data.toString().length() > 15_500) progress.remove(0);
        if (progress.isEmpty()) data.remove("executedToolReceipts");
    }

    private static String boundedHostText(String value, int limit) {
        String clean = com.javaclaw.util.SensitiveDataRedactor.redactText(value).replaceAll("[\\p{Cntrl}]", " ").strip();
        int end = Math.min(limit, clean.length());
        if (end > 0 && end < clean.length() && Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end);
    }

    private record HostStop(String code, String message, String evidenceRef) { }

    private static List<JsonNode> unknownEffects(List<RunEventEnvelope> events, RunId owner) {
        var receipts = new LinkedHashMap<String, JsonNode>();
        var starts = new LinkedHashMap<String, JsonNode>();
        Set<String> reconciled = new HashSet<>();
        for (var event : events) {
            if (!event.producer().equals("framework.core")) continue;
            var value = event.payload();
            String invocation = value.path("invocationId").asText();
            if (event.type().equals("core.tool.started")) starts.put(invocation, value);
            if (event.type().equals("core.tool.receipt")) receipts.put(invocation, value);
            if (event.type().equals("core.effect.reconciled") && value.path("outcome").asText().equals("SATISFIED"))
                reconciled.add(value.path("actionInvocationId").asText(invocation));
        }
        List<JsonNode> unknown = new ArrayList<>();
        for (var entry : starts.entrySet()) {
            var start = entry.getValue();
            String tool = start.path("tool").asText();
            if (start.path("idempotent").asBoolean() || reconciled.contains(entry.getKey())
                    || CrossModeBusinessFence.BROWSER_OBSERVATION_WAITS.contains(tool)
                    || !tool.startsWith("web_") && !tool.startsWith("site_")
                        && !tool.startsWith("desktop_session_")) continue;
            JsonNode receipt = receipts.get(entry.getKey());
            String status = receipt == null ? "PENDING" : receipt.path("status").asText("UNKNOWN");
            String delivery = receipt == null ? "MAYBE_SENT" : receipt.path("metadata").path("delivery").asText("");
            if (status.equals("UNKNOWN") || status.equals("PENDING") || delivery.equals("MAYBE_SENT")
                    || status.equals("FAILED") && !delivery.equals("NOT_SENT")) {
                unknown.add(JsonNodeFactory.instance.objectNode().put("sourceRunId", owner.value())
                        .put("invocationId", entry.getKey())
                        .put("tool", tool).put("status", status).put("delivery", delivery)
                        .put("uncertainty", "DELIVERY_UNKNOWN")
                        .put("mode", tool.startsWith("desktop_session_") ? "DESKTOP" : "BROWSER")
                        .put("target", receipt == null ? "" : receipt.path("target").asText().substring(0,
                                Math.min(256, receipt.path("target").asText().length())))
                        .put("evidenceRef", "core.tool.started:" + owner.value() + ":" + entry.getKey()));
            }
        }
        return unknown;
    }
}
