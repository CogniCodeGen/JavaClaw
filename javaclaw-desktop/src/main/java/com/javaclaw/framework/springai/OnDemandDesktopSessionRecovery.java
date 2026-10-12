package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.AgentStep;
import com.javaclaw.framework.api.RunEventEnvelope;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Session liveness comes from the owner-scoped host inventory, never model text. */
final class OnDemandDesktopSessionRecovery {
    record FrameRecovery(long failureSequence, int frameFailures, int discoveries, int failedOpens) {
        boolean required() { return failureSequence > 0; }
        boolean exhausted() { return frameFailures > 1 || discoveries > 1 || failedOpens > 0; }
    }
    private static final Set<String> SESSION_TOOLS = Set.of("desktop_session_observe",
            "desktop_session_snapshot", "desktop_session_click", "desktop_session_type",
            "desktop_session_key", "desktop_session_scroll", "desktop_session_takeover",
            "desktop_session_close");
    private static final Set<String> SESSION_FAILURE_CODES = Set.of("ACCESS_DENIED",
            "INVALID_ARGUMENTS", "INVALID_SESSION", "SESSION_NOT_FOUND", "SESSION_EXPIRED");

    private OnDemandDesktopSessionRecovery() { }

    static boolean requiresSession(String tool) { return SESSION_TOOLS.contains(tool); }

    /** A new handle or frame token never resets a failed recovery episode; only a verified frame does. */
    static FrameRecovery frameRecovery(List<AgentStep> steps, List<RunEventEnvelope> events) {
        if (steps.isEmpty()) return new FrameRecovery(0, 0, 0, 0);
        String owner = steps.getFirst().turnId().value();
        Map<String, JsonNode> receipts = new HashMap<>();
        for (var event : events) if (event.runId().equals(owner)
                && event.type().equals("core.tool.receipt") && event.schemaVersion() == 1
                && event.producer().equals("framework.core"))
            receipts.put(event.payload().path("invocationId").asText(), event.payload());
        long failure = 0;
        int failures = 0, discoveries = 0, failedOpens = 0;
        for (var step : steps.stream().sorted(java.util.Comparator.comparingLong(AgentStep::startSequence)).toList()) {
            if (!step.turnId().value().equals(owner) || step.kind() != AgentStep.Kind.TOOL
                    || step.state() != AgentStep.State.COMPLETED || step.input() == null || step.output() == null) continue;
            String tool = step.input().path("tool").asText();
            JsonNode receipt = receipts.get(step.input().path("invocationId").asText());
            if (receipt == null || !tool.equals(receipt.path("tool").asText())) continue;
            JsonNode raw = step.output().path("rawOutput");
            String session = step.input().path("arguments").path("sessionId").asText();
            if (tool.equals("desktop_session_observe") && receipt.path("status").asText().equals("OBSERVED")
                    && step.output().path("status").asText().equals("SUCCEEDED")
                    && !session.isBlank() && session.equals(receipt.path("metadata").path("sessionId").asText())
                    && raw.path("kind").asText().equals("desktop.observation")
                    && !receipt.path("metadata").path("observationId").asText().isBlank()) {
                failure = 0; failures = 0; discoveries = 0; failedOpens = 0;
            } else if (Set.of("desktop_session_observe", "desktop_session_snapshot").contains(tool)
                    && raw.path("schemaVersion").asInt() == 1 && raw.path("kind").asText().equals("desktop.error")
                    && tool.equals(raw.path("tool").asText()) && !session.isBlank()
                    && session.equals(raw.path("sessionId").asText())
                    && Set.of("NO_FRAME", "TARGET_CHANGED").contains(raw.path("errorCode").asText())) {
                failure = step.lastSequence(); failures++;
            } else if (failure > 0 && tool.equals("desktop_session_targets")) discoveries++;
            else if (failure > 0 && tool.equals("desktop_session_open")
                    && !step.output().path("status").asText().equals("SUCCEEDED")) failedOpens++;
        }
        return new FrameRecovery(failure, failures, discoveries, failedOpens);
    }

    static boolean noLiveSessions(List<JsonNode> context) {
        return completeInventory(context).map(Set::isEmpty).orElse(false);
    }

    static boolean missingSession(String sessionId, List<JsonNode> context) {
        return !sessionId.isBlank() && completeInventory(context)
                .map(ids -> !ids.contains(sessionId)).orElse(false);
    }

    /** Unknown, partial, malformed or conflicting inventories cannot invalidate a handle. */
    private static Optional<Set<String>> completeInventory(List<JsonNode> context) {
        Set<String> inventory = null;
        for (JsonNode item : context) {
            if (item == null || !item.isObject()
                    || !item.path("kind").asText().equals("desktop.sessions.current")
                    || !item.path("known").isBoolean() || !item.path("known").booleanValue()
                    || !item.path("complete").isBoolean() || !item.path("complete").booleanValue()
                    || !item.path("sessionIds").isArray()) continue;
            Set<String> ids = new HashSet<>();
            for (JsonNode id : item.path("sessionIds")) {
                if (!id.isTextual() || id.asText().isBlank() || id.asText().length() > 512
                        || !ids.add(id.asText())) return Optional.empty();
            }
            if (inventory != null && !inventory.equals(ids)) return Optional.empty();
            inventory = ids;
        }
        return Optional.ofNullable(inventory).map(Set::copyOf);
    }

    /**
     * ACCESS_DENIED alone can mean missing permission. Only a complete live inventory
     * excluding this exact argument plus a settled host raw error proves session recovery.
     */
    static long invalidSessionFailureSequence(List<AgentStep> steps,
            List<RunEventEnvelope> events, List<JsonNode> runtimeContext) {
        if (steps.isEmpty() || completeInventory(runtimeContext).isEmpty()) return 0;
        String owner = steps.getFirst().turnId().value();
        Map<String, JsonNode> receipts = new HashMap<>();
        for (RunEventEnvelope event : events) {
            if (event.runId().equals(owner) && event.type().equals("core.tool.receipt")
                    && event.schemaVersion() == 1 && event.producer().equals("framework.core")) {
                String invocation = event.payload().path("invocationId").asText("");
                if (!invocation.isBlank()) receipts.put(invocation, event.payload());
            }
        }
        long failure = 0;
        long opened = 0;
        for (AgentStep step : steps) {
            if (!step.turnId().value().equals(owner) || step.kind() != AgentStep.Kind.TOOL
                    || step.state() != AgentStep.State.COMPLETED
                    || step.input() == null || step.output() == null) continue;
            String tool = step.input().path("tool").asText("");
            JsonNode arguments = step.input().path("arguments");
            JsonNode raw = step.output().path("rawOutput");
            JsonNode receipt = receipts.get(step.input().path("invocationId").asText(""));
            if (receipt == null || !tool.equals(receipt.path("tool").asText())) continue;
            if (tool.equals("desktop_session_open")
                    && step.output().path("status").asText().equals("SUCCEEDED")
                    && receipt.path("status").asText().equals("ACCEPTED")
                    && receipt.path("operation").asText().equals("open")
                    && !receipt.path("metadata").path("sessionId").asText().isBlank()
                    && !arguments.path("targetId").asText().isBlank()
                    && arguments.path("targetId").asText()
                            .equals(receipt.path("metadata").path("targetId").asText())
                    && raw.path("schemaVersion").isInt() && raw.path("schemaVersion").intValue() == 1
                    && raw.path("kind").asText().equals("desktop.session")
                    && raw.path("sessionId").asText()
                            .equals(receipt.path("metadata").path("sessionId").asText())) {
                opened = Math.max(opened, step.lastSequence());
            }
            String session = arguments.path("sessionId").asText("");
            if (requiresSession(tool) && missingSession(session, runtimeContext)
                    && step.output().path("status").asText().equals("FAILED")
                    && receipt.path("status").asText().equals("FAILED")
                    && receipt.path("operation").asText().equals(tool.substring("desktop_session_".length()))
                    && raw.path("schemaVersion").isInt() && raw.path("schemaVersion").intValue() == 1
                    && raw.path("kind").asText().equals("desktop.error")
                    && raw.path("tool").asText().equals(tool)
                    && raw.path("sessionId").asText().equals(session)
                    && raw.path("admission").asText().equals("FAILED")
                    && SESSION_FAILURE_CODES.contains(raw.path("errorCode").asText())) {
                failure = Math.max(failure, step.lastSequence());
            }
        }
        return failure > opened ? failure : 0;
    }
}
