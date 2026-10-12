package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A failed native frame permits one rediscovery, followed by open/observe or an explicit stop. */
public final class DesktopFrameRecoveryLimit {
    private static final Set<String> TOOLS = Set.of("desktop_session_observe",
            "desktop_session_snapshot", "desktop_session_targets", "desktop_session_open");

    private DesktopFrameRecoveryLimit() { }

    public static boolean discoveryCompleted(RunId owner, List<RunEventEnvelope> events,
            String currentInvocationId) {
        return recovery(owner, events, currentInvocationId).discovered();
    }

    private static Recovery recovery(RunId owner, List<RunEventEnvelope> events,
            String currentInvocationId) {
        Map<String, List<RunEventEnvelope>> grouped = new LinkedHashMap<>();
        for (var event : events) {
            if (!owner.value().equals(event.runId())
                    || !Set.of("core.tool.started", "core.tool.completed", "core.tool.receipt").contains(event.type())) continue;
            String invocation = event.payload().path("invocationId").asText();
            if (invocation.isBlank() || invocation.equals(currentInvocationId)) continue;
            grouped.computeIfAbsent(invocation, ignored -> new ArrayList<>()).add(event);
        }
        List<Triple> settled = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            var stages = entry.getValue();
            if (stages.size() != 3) continue;
            var started = unique(stages, "core.tool.started", 1);
            var completed = unique(stages, "core.tool.completed", 2);
            var receipt = unique(stages, "core.tool.receipt", 1);
            if (started == null || completed == null || receipt == null
                    || started.sequence() >= completed.sequence() || completed.sequence() >= receipt.sequence()
                    || !started.payload().path("trustedDesktopTool").isBoolean()
                    || !started.payload().path("trustedDesktopTool").booleanValue()) continue;
            String tool = started.payload().path("tool").asText();
            var raw = completed.payload().path("output");
            if (!TOOLS.contains(tool) || !tool.equals(completed.payload().path("tool").asText())
                    || !tool.equals(receipt.payload().path("tool").asText())
                    || !receipt.payload().path("operation").asText().equals(tool.substring("desktop_session_".length()))
                    || !receipt.payload().path("evidenceRef").asText().equals(
                        "core.tool.completed:" + owner.value() + ":" + entry.getKey())
                    || !raw.path("schemaVersion").isInt() || raw.path("schemaVersion").intValue() != 1
                    || !raw.path("protocol").asText().equals("computer-use")) continue;
            settled.add(new Triple(started, completed, receipt));
        }
        settled.sort(Comparator.comparingLong(value -> value.receipt().sequence()));
        Map<String, DesktopObservationBaseline.Frame> frames = new HashMap<>();
        DesktopObservationBaseline.fromEvents(events.stream().filter(event -> owner.value().equals(event.runId())
                        && !event.payload().path("invocationId").asText().equals(currentInvocationId)).toList())
                .forEach(frame -> frames.put(frame.invocationId(), frame));
        Map<String, String> applications = new HashMap<>();
        String failedSession = "", failedApplication = "";
        long failureSequence = 0, firstFailureSequence = 0, failureAtMillis = 0;
        boolean discovered = false;
        for (var triple : settled) {
            var start = triple.started().payload();
            var completion = triple.completed().payload();
            var receipt = triple.receipt().payload();
            var raw = completion.path("output");
            String tool = start.path("tool").asText();
            String session = raw.path("sessionId").asText();
            if (tool.equals("desktop_session_open") && completion.path("status").asText().equals("SUCCEEDED")
                    && receipt.path("status").asText().equals("ACCEPTED")
                    && raw.path("kind").asText().equals("desktop.session") && !session.isBlank()
                    && session.equals(receipt.path("metadata").path("sessionId").asText())
                    && !start.path("arguments").path("targetId").asText().isBlank()
                    && start.path("arguments").path("targetId").asText().equals(raw.path("target").path("targetId").asText())
                    && raw.path("target").path("targetId").asText().equals(receipt.path("metadata").path("targetId").asText())) {
                String application = raw.path("target").path("applicationId").asText();
                if (!application.isBlank()) applications.put(session, application);
            }
            if (tool.equals("desktop_session_observe")) {
                var frame = frames.get(start.path("invocationId").asText());
                if (frame != null) {
                    String application = raw.path("applicationId").asText();
                    if (!application.isBlank()) applications.put(frame.sessionId(), application);
                    boolean eligibleRecoveryFrame = frame.sessionId().equals(failedSession)
                            || !failedApplication.isBlank() && failedApplication.equalsIgnoreCase(application);
                    if (failureSequence > 0 && eligibleRecoveryFrame && triple.started().sequence() > failureSequence
                            && frame.capturedAtMillis() > failureAtMillis) {
                        failureSequence = 0; firstFailureSequence = 0; discovered = false;
                        failedSession = ""; failedApplication = "";
                    }
                    continue;
                }
            }
            if (Set.of("desktop_session_observe", "desktop_session_snapshot").contains(tool)
                    && completion.path("status").asText().equals("FAILED")
                    && receipt.path("status").asText().equals("FAILED")
                    && raw.path("kind").asText().equals("desktop.error")
                    && raw.path("admission").asText().equals("FAILED")
                    && raw.path("tool").asText().equals(tool) && !session.isBlank()
                    && session.equals(start.path("arguments").path("sessionId").asText())
                    && Set.of("NO_FRAME", "TARGET_CHANGED").contains(raw.path("errorCode").asText())) {
                if (failureSequence == 0) {
                    firstFailureSequence = triple.receipt().sequence();
                    failedSession = session;
                    failedApplication = applications.getOrDefault(session, "");
                }
                failureSequence = triple.receipt().sequence();
                failureAtMillis = triple.receipt().timestamp().toEpochMilli();
            } else if (failureSequence > 0 && triple.started().sequence() > failureSequence
                    && tool.equals("desktop_session_targets")
                    && (completion.path("status").asText().equals("SUCCEEDED")
                        && receipt.path("status").asText().equals("OBSERVED")
                        && raw.path("kind").asText().equals("desktop.targets") && raw.path("targets").isArray()
                        || completion.path("status").asText().equals("FAILED")
                            && receipt.path("status").asText().equals("FAILED")
                            && raw.path("kind").asText().equals("desktop.error")
                            && raw.path("tool").asText().equals(tool)
                            && raw.path("admission").asText().equals("FAILED"))) discovered = true;
        }
        return new Recovery(failureSequence > 0, firstFailureSequence, failureSequence > 0 && discovered);
    }

    public static void assertDiscoveryAllowed(RunId owner, List<RunEventEnvelope> events,
            String currentInvocationId) {
        var recovery = recovery(owner, events, currentInvocationId);
        // This check and the caller's durable start are serialized by the Run acceptance fence.
        // An earlier in-flight discovery owns the one attempt even before it has a receipt.
        boolean reserved = recovery.required() && events.stream().anyMatch(event ->
                owner.value().equals(event.runId()) && event.type().equals("core.tool.started")
                        && event.schemaVersion() == 1 && event.producer().equals("framework.core")
                        && event.sequence() > recovery.firstFailureSequence()
                        && event.payload().path("tool").asText().equals("desktop_session_targets")
                        && event.payload().path("trustedDesktopTool").isBoolean()
                        && event.payload().path("trustedDesktopTool").booleanValue()
                        && !event.payload().path("invocationId").asText().isBlank()
                        && !event.payload().path("invocationId").asText().equals(currentInvocationId));
        if (recovery.discovered() || reserved)
            throw new ToolPermissionDeniedException("DESKTOP_RECOVERY_EXHAUSTED: one target rediscovery is already "
                    + "reserved or completed for the failed frame; open a discovered eligible target and observe it, "
                    + "or stop and clarify instead of rediscovering again");
    }

    private static RunEventEnvelope unique(List<RunEventEnvelope> events, String type, int schema) {
        var matches = events.stream().filter(event -> event.type().equals(type)).toList();
        if (matches.size() != 1) return null;
        var event = matches.getFirst();
        return event.schemaVersion() == schema && event.producer().equals("framework.core") ? event : null;
    }

    private record Recovery(boolean required, long firstFailureSequence, boolean discovered) { }

    private record Triple(RunEventEnvelope started, RunEventEnvelope completed, RunEventEnvelope receipt) { }
}
