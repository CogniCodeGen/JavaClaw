package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Journal-backed desktop loop limit. Fresh handle IDs do not constitute visible progress. */
public final class DesktopActionProgressLimit {
    private static final String CLICK = "desktop_session_click";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> INPUTS = Set.of(CLICK, "desktop_session_type",
            "desktop_session_key", "desktop_session_scroll");

    private DesktopActionProgressLimit() { }

    /** Check before the next model call, including after a backend refused a duplicate dispatch. */
    public static void assertProgress(RunId owner, List<RunEventEnvelope> events) {
        stalled(owner, events).ifPresent(stall -> { throw new DesktopNoProgressException(stall.refs()); });
    }

    /** Keep direct calls and concurrent callers behind the same durable dispatch fence. */
    public static void assertClickAllowed(RunId owner, List<RunEventEnvelope> events) {
        assertProgress(owner, events);
    }

    private static Optional<Stall> stalled(RunId owner, List<RunEventEnvelope> all) {
        List<RunEventEnvelope> events = all.stream().filter(e -> e.runId().equals(owner.value())).toList();
        Map<String, List<RunEventEnvelope>> grouped = new LinkedHashMap<>();
        for (var event : events) {
            if (!Set.of("core.tool.started", "core.tool.completed", "core.tool.receipt").contains(event.type())) continue;
            String id = event.payload().path("invocationId").asText();
            if (!id.isBlank()) grouped.computeIfAbsent(id, ignored -> new ArrayList<>()).add(event);
        }
        List<Input> inputs = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            var stages = entry.getValue();
            if (stages.size() != 3) continue;
            var start = unique(stages, "core.tool.started", 1);
            var done = unique(stages, "core.tool.completed", 2);
            var receipt = unique(stages, "core.tool.receipt", 1);
            if (start == null || done == null || receipt == null
                    || start.sequence() >= done.sequence() || done.sequence() >= receipt.sequence()
                    || start.timestamp().isAfter(done.timestamp()) || done.timestamp().isAfter(receipt.timestamp())) continue;
            JsonNode s = start.payload(), d = done.payload(), r = receipt.payload(), raw = d.path("output");
            String tool = s.path("tool").asText();
            if (!INPUTS.contains(tool) || !s.path("trustedDesktopTool").asBoolean(false)
                    || !tool.equals(d.path("tool").asText()) || !tool.equals(r.path("tool").asText())
                    || !r.path("operation").asText().equals(tool.substring("desktop_session_".length()))
                    || !r.path("evidenceRef").asText().equals("core.tool.completed:" + owner.value() + ":" + entry.getKey())
                    || !raw.path("protocol").asText().equals("computer-use") || raw.path("schemaVersion").asInt() != 1) continue;
            inputs.add(new Input(start, done, receipt));
        }
        inputs.sort(Comparator.comparingLong(input -> input.start().sequence()));
        if (inputs.isEmpty()) return Optional.empty();
        Input latest = inputs.getLast();
        // This backend rejection has already compared fresh, owned pixels and the exact native control.
        JsonNode lastRaw = latest.done().payload().path("output");
        if (latest.start().payload().path("tool").asText().equals(CLICK)
                && latest.done().payload().path("status").asText().equals("FAILED")
                && lastRaw.path("kind").asText().equals("desktop.action")
                && lastRaw.path("errorCode").asText().equals("DESKTOP_NO_PROGRESS")
                && lastRaw.path("delivery").asText().equals("NOT_SENT")
                && latest.receipt().payload().path("status").asText().equals("FAILED")
                && latest.receipt().payload().path("metadata").path("reasonCode").asText().equals("NO_PROGRESS")
                && latest.receipt().payload().path("metadata").path("delivery").asText().equals("NOT_SENT")
                && lastRaw.path("sessionId").asText().equals(latest.start().payload().path("arguments").path("sessionId").asText())
                && !lastRaw.path("dispatchAttempted").asBoolean(true))
            return Optional.of(new Stall(List.of(latest.receipt().payload().path("evidenceRef").asText())));

        // Compatibility/restart fallback: two settled equivalent clicks, each followed by an unchanged
        // host frame, suffice to stop a third. Never interpret unchanged pixels as permission to retry.
        if (inputs.size() < 2) return Optional.empty();
        List<DesktopObservationBaseline.Frame> frames = DesktopObservationBaseline.fromEvents(events);
        Input previous = inputs.get(inputs.size() - 2);
        Optional<Attempt> first = unchanged(previous, latest.start().sequence(), frames, events);
        Optional<Attempt> second = unchanged(latest, Long.MAX_VALUE, frames, events);
        if (first.isEmpty() || second.isEmpty() || !first.get().identity().equals(second.get().identity()))
            return Optional.empty();
        return Optional.of(new Stall(List.of(first.get().actionRef(), first.get().observationRef(),
                second.get().actionRef(), second.get().observationRef())));
    }

    private static Optional<Attempt> unchanged(Input input, long beforeSequence,
            List<DesktopObservationBaseline.Frame> frames, List<RunEventEnvelope> events) {
        JsonNode start = input.start().payload(), raw = input.done().payload().path("output");
        JsonNode args = start.path("arguments"), receipt = input.receipt().payload();
        if (!start.path("tool").asText().equals(CLICK)
                || !input.done().payload().path("status").asText().equals("SUCCEEDED")
                || !receipt.path("status").asText().equals("ACCEPTED")
                || !receipt.path("metadata").path("delivery").asText().equals("SENT")
                || !raw.path("kind").asText().equals("desktop.action")
                || !raw.path("delivery").asText().equals("SENT")
                || !raw.path("mode").asText().equals("BACKGROUND_SEMANTIC")
                || args.path("button").asInt(1) != 1 || args.path("clicks").asInt(1) != 1)
            return Optional.empty();
        String session = args.path("sessionId").asText(), observation = args.path("observationId").asText();
        var before = frames.stream().filter(f -> f.sequence() < input.start().sequence()
                && f.sessionId().equals(session) && f.observationId().equals(observation))
                .max(Comparator.comparingLong(DesktopObservationBaseline.Frame::sequence)).orElse(null);
        if (before == null || !raw.path("targetId").asText().equals(before.targetId())
                || raw.path("windowGeneration").asLong() != before.windowGeneration()) return Optional.empty();
        var after = frames.stream().filter(f -> f.sequence() > input.receipt().sequence()
                && f.sequence() < beforeSequence && f.sessionId().equals(session)
                && f.targetId().equals(before.targetId())
                && f.capturedAtMillis() > input.receipt().timestamp().toEpochMilli())
                .max(Comparator.comparingLong(DesktopObservationBaseline.Frame::sequence)).orElse(null);
        if (after == null || after.windowGeneration() != before.windowGeneration()
                || after.contentRevision() != before.contentRevision()) return Optional.empty();
        JsonNode observationRaw = observationRaw(events, before);
        JsonNode afterRaw = observationRaw(events, after);
        if (observationRaw == null || afterRaw == null) return Optional.empty();
        // Old/truncated projections cannot prove that a matching AX control is unique.
        // The live backend still compares its actual captured catalog and persists an explicit refusal.
        if (observationRaw.path("elementsTruncated").asBoolean(true)
                || observationRaw.path("elementCount").asInt(-1) != observationRaw.path("elements").size())
            return Optional.empty();
        JsonNode surface = surface(observationRaw, before), afterSurface = surface(afterRaw, after);
        if (surface == null || !surface.equals(afterSurface)) return Optional.empty();
        String elementId = args.path("elementId").asText();
        if (!elementId.startsWith(observation + ":e")) return Optional.empty();
        ObjectNode identity = JsonNodeFactory.instance.objectNode()
                .put("sessionId", session).put("targetId", before.targetId())
                .put("generation", before.windowGeneration()).put("revision", before.contentRevision())
                .put("button", args.path("button").asInt(1)).put("clicks", args.path("clicks").asInt(1));
        identity.set("surface", surface);
        identity.put("frameWidth", observationRaw.path("frame").path("width").asLong())
                .put("frameHeight", observationRaw.path("frame").path("height").asLong());
        if (observationRaw.path("frame").path("width").asLong() != afterRaw.path("frame").path("width").asLong()
                || observationRaw.path("frame").path("height").asLong() != afterRaw.path("frame").path("height").asLong())
            return Optional.empty();
        {
            JsonNode element = null;
            String source = "elements";
            for (JsonNode candidate : observationRaw.path(source)) {
                if (elementId.equals(candidate.path("id").asText())) {
                    if (element != null) return Optional.empty();
                    element = candidate;
                }
            }
            if (element == null || element.path("role").asText().isBlank()
                    || element.path("label").asText().isBlank()
                    || (element.path("actions").asInt() & 1) == 0) return Optional.empty();
            var control = identity.putObject("control").put("source", source);
            for (String field : List.of("role", "label", "x", "y", "width", "height", "actions", "pressable"))
                control.set(field, element.path(field).deepCopy());
            int matches = 0;
            for (JsonNode candidate : observationRaw.path(source)) {
                boolean same = true;
                for (String field : List.of("role", "label", "x", "y", "width", "height", "actions", "pressable"))
                    same &= candidate.path(field).equals(element.path(field));
                if (same) matches++;
            }
            if (matches != 1) return Optional.empty();
        }
        return Optional.of(new Attempt(identity, receipt.path("evidenceRef").asText(),
                "core.tool.completed:" + after.runId() + ":" + after.invocationId()));
    }

    private static JsonNode observationRaw(List<RunEventEnvelope> events, DesktopObservationBaseline.Frame frame) {
        var start = events.stream().filter(e -> e.type().equals("core.tool.started")
                && e.payload().path("invocationId").asText().equals(frame.invocationId())).findFirst().orElse(null);
        if (start == null || !start.payload().path("trustedDesktopTool").isBoolean()
                || !start.payload().path("trustedDesktopTool").booleanValue()) return null;
        JsonNode raw = events.stream().filter(e -> e.type().equals("core.tool.completed")
                        && e.payload().path("invocationId").asText().equals(frame.invocationId()))
                .findFirst().map(e -> e.payload().path("output")).orElse(null);
        var receipt = events.stream().filter(e -> e.type().equals("core.tool.receipt")
                && e.payload().path("invocationId").asText().equals(frame.invocationId())).findFirst().orElse(null);
        if (raw == null || receipt == null) return null;
        try {
            JsonNode bound = JSON.readTree(receipt.payload().path("metadata").path("interactionStage").asText());
            return raw.path("interactionStage").equals(bound) ? raw : null;
        } catch (Exception malformed) { return null; }
    }

    private static JsonNode surface(JsonNode raw, DesktopObservationBaseline.Frame frame) {
        JsonNode stage = raw.path("interactionStage");
        if (!stage.path("mode").asText().equals("DESKTOP")
                || stage.path("schemaVersion").asInt() != 1 || !stage.path("kind").asText().equals("observation")
                || !stage.path("contextId").asText().equals(frame.sessionId())
                || !stage.path("targetId").asText().equals(frame.targetId())
                || !stage.path("observationId").asText().equals(frame.observationId())
                || stage.path("generation").asLong() != frame.windowGeneration()
                || stage.path("contentRevision").asLong() != frame.contentRevision()
                || stage.path("capturedAtMillis").asLong() != frame.capturedAtMillis()
                || stage.path("frameWidth").asLong() != raw.path("frame").path("width").asLong()
                || stage.path("frameHeight").asLong() != raw.path("frame").path("height").asLong()
                || !stage.path("applicationId").asText().equals(raw.path("applicationId").asText())
                || stage.path("runtimeId").asText().isBlank() || stage.path("surfaceId").asText().isBlank()) return null;
        return JsonNodeFactory.instance.objectNode().put("runtimeId", stage.path("runtimeId").asText())
                .put("surfaceId", stage.path("surfaceId").asText())
                .put("applicationId", stage.path("applicationId").asText());
    }

    private static RunEventEnvelope unique(List<RunEventEnvelope> stages, String type, int schema) {
        var matches = stages.stream().filter(e -> e.type().equals(type)).toList();
        if (matches.size() != 1) return null;
        var event = matches.getFirst();
        return event.schemaVersion() == schema && event.producer().equals("framework.core") ? event : null;
    }

    private record Input(RunEventEnvelope start, RunEventEnvelope done, RunEventEnvelope receipt) { }
    private record Attempt(JsonNode identity, String actionRef, String observationRef) { }
    private record Stall(List<String> refs) { }
}
