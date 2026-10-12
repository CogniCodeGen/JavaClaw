package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.EffectReceiptV1;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A host-bound frame that can establish where a subsequent desktop decision starts.
 * This proves observation of a window, never that an earlier action achieved its purpose.
 */
public final class DesktopObservationBaseline {
    private static final String TOOL = "desktop_session_observe";

    private DesktopObservationBaseline() { }

    /**
     * Replays complete invocation triples. The raw output is authoritative; modelOutput,
     * display messages and model-produced events cannot supply a frame or a missing binding.
     */
    public static List<Frame> fromEvents(List<RunEventEnvelope> events) {
        if (events == null || events.isEmpty()) return List.of();
        Map<Invocation, List<RunEventEnvelope>> invocations = new LinkedHashMap<>();
        for (RunEventEnvelope event : events) {
            if (event == null || !stage(event.type())) continue;
            String invocation = text(event.payload(), "invocationId");
            if (invocation.isBlank()) continue;
            invocations.computeIfAbsent(new Invocation(event.runId(), invocation),
                    ignored -> new ArrayList<>()).add(event);
        }
        List<Frame> frames = new ArrayList<>();
        for (var entry : invocations.entrySet()) {
            List<RunEventEnvelope> invocation = entry.getValue();
            if (invocation.size() != 3) continue; // Duplicate or conflicting stages are ambiguous.
            RunEventEnvelope started = unique(invocation, "core.tool.started", 1);
            RunEventEnvelope completed = unique(invocation, "core.tool.completed", 2);
            RunEventEnvelope receipt = unique(invocation, "core.tool.receipt", 1);
            if (started == null || completed == null || receipt == null
                    || started.sequence() >= completed.sequence()
                    || completed.sequence() >= receipt.sequence()
                    || started.timestamp().isAfter(completed.timestamp())
                    || completed.timestamp().isAfter(receipt.timestamp())
                    || !text(completed.payload(), "status").equals("SUCCEEDED")) continue;
            Optional<Frame> parsed = validate(entry.getKey().runId(), entry.getKey().invocationId(),
                    started.payload().path("arguments"), completed.payload().path("output"),
                    receipt.payload(), receipt.sequence());
            if (parsed.isEmpty()) continue;
            Frame frame = parsed.get();
            try {
                if (frame.observedAtMillis() < started.timestamp().toEpochMilli()
                        || frame.observedAtMillis() > receipt.timestamp().toEpochMilli()) continue;
            } catch (ArithmeticException invalidTimestamp) { continue; }
            frames.add(frame);
        }
        // A host observation ID and session target cannot acquire conflicting identities.
        List<Frame> unambiguous = frames.stream().filter(candidate -> frames.stream().noneMatch(other ->
                candidate.runId().equals(other.runId())
                        && ((candidate.sessionId().equals(other.sessionId())
                                && !candidate.targetId().equals(other.targetId()))
                            || (candidate.observationId().equals(other.observationId())
                                && !sameCapture(candidate, other))))).toList();
        // Callers replay one Run. Preserve Run grouping when a history contains several Runs.
        Map<String, List<Frame>> runs = new LinkedHashMap<>();
        unambiguous.forEach(frame -> runs.computeIfAbsent(frame.runId(), ignored -> new ArrayList<>())
                .add(frame));
        List<Frame> ordered = new ArrayList<>();
        runs.values().forEach(run -> run.stream().sorted(java.util.Comparator.comparingLong(Frame::sequence))
                .forEach(ordered::add));
        return List.copyOf(ordered);
    }

    public static Optional<Frame> latest(List<RunEventEnvelope> events) {
        List<Frame> frames = fromEvents(events);
        return frames.isEmpty() ? Optional.empty() : Optional.of(frames.getLast());
    }

    /** The caller must already have verified the exact host tool/receipt source. */
    public static Optional<Frame> fromTrustedResult(RunId runId, String invocationId,
            JsonNode arguments, JsonNode raw, EffectReceiptV1 receipt) {
        if (runId == null || receipt == null) return Optional.empty();
        return validate(runId.value(), invocationId, arguments, raw, receipt.toJson(), 0);
    }

    private static Optional<Frame> validate(String runId, String invocationId,
            JsonNode arguments, JsonNode raw, JsonNode receipt, long sequence) {
        if (!identifier(runId) || !identifier(invocationId) || arguments == null || !arguments.isObject()
                || raw == null || !raw.isObject() || receipt == null || !receipt.isObject()
                || !text(receipt, "tool").equals(TOOL)
                || !text(receipt, "invocationId").equals(invocationId)
                || !text(receipt, "operation").equals("observe")
                || !text(receipt, "status").equals("OBSERVED")
                || !text(receipt, "evidenceRef").equals("core.tool.completed:" + runId + ":" + invocationId)
                || !text(raw, "protocol").equals("computer-use")
                || number(raw, "schemaVersion") != 1
                || !text(raw, "kind").equals("desktop.observation")) return Optional.empty();
        JsonNode metadata = receipt.path("metadata");
        JsonNode frame = raw.path("frame");
        if (!metadata.isObject() || !frame.isObject()) return Optional.empty();
        String session = text(arguments, "sessionId");
        String target = text(raw, "targetId");
        String observation = text(raw, "observationId");
        String application = text(raw, "application");
        if (!identifier(session) || !identifier(target) || !identifier(observation)
                || application.isBlank() || !application.equals(text(receipt, "target"))
                || !session.equals(text(raw, "sessionId"))
                || !session.equals(text(metadata, "sessionId"))
                || !target.equals(text(metadata, "targetId"))
                || !target.equals(text(frame, "targetId"))
                || !observation.equals(text(metadata, "observationId"))) return Optional.empty();
        String applicationId = text(raw, "applicationId");
        String receiptApplicationId = text(metadata, "applicationId");
        if ((!applicationId.isBlank() || !receiptApplicationId.isBlank())
                && !applicationId.equals(receiptApplicationId)) return Optional.empty();
        long generation = number(raw, "windowGeneration");
        long revision = number(raw, "contentRevision");
        long capturedAt = number(raw, "capturedAtMillis");
        long observedAt;
        try { observedAt = Instant.parse(text(receipt, "observedAt")).toEpochMilli(); }
        catch (RuntimeException invalidTime) { return Optional.empty(); }
        if (generation < 1 || revision < 1 || capturedAt < 1 || observedAt < capturedAt
                || number(frame, "width") < 1 || number(frame, "height") < 1
                || generation != number(frame, "windowGeneration")
                || revision != number(frame, "contentRevision")
                || capturedAt != number(frame, "capturedAtMillis")
                || generation != metadataNumber(metadata, "windowGeneration")
                || revision != metadataNumber(metadata, "contentRevision")
                || capturedAt != metadataNumber(metadata, "capturedAtMillis")) return Optional.empty();
        return Optional.of(new Frame(runId, invocationId, session, target, observation,
                generation, revision, capturedAt, observedAt, sequence));
    }

    private static RunEventEnvelope unique(List<RunEventEnvelope> events, String type, int schema) {
        List<RunEventEnvelope> matches = events.stream().filter(event -> event.type().equals(type)).toList();
        if (matches.size() != 1) return null;
        RunEventEnvelope event = matches.getFirst();
        JsonNode payload = event.payload();
        if (event.schemaVersion() != schema || !event.producer().equals("framework.core")
                || !text(payload, "tool").equals(TOOL)) return null;
        // Old host journals did not have this marker. Explicit denials never inherit that compatibility.
        if (payload.has("trustedDesktopTool")
                && (!payload.path("trustedDesktopTool").isBoolean()
                    || !payload.path("trustedDesktopTool").booleanValue())) return null;
        return event;
    }

    private static boolean stage(String type) {
        return type.equals("core.tool.started") || type.equals("core.tool.completed")
                || type.equals("core.tool.receipt");
    }

    private static boolean sameCapture(Frame first, Frame second) {
        return first.sessionId().equals(second.sessionId()) && first.targetId().equals(second.targetId())
                && first.windowGeneration() == second.windowGeneration()
                && first.contentRevision() == second.contentRevision()
                && first.capturedAtMillis() == second.capturedAtMillis();
    }

    private static String text(JsonNode value, String field) {
        JsonNode fieldValue = value.path(field);
        return fieldValue.isTextual() ? fieldValue.textValue() : "";
    }

    private static boolean identifier(String value) {
        return value != null && !value.isBlank() && value.length() <= 512
                && value.equals(value.strip()) && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static long number(JsonNode value, String field) {
        JsonNode number = value.path(field);
        return number.isIntegralNumber() && number.canConvertToLong() ? number.longValue() : -1;
    }

    private static long metadataNumber(JsonNode value, String field) {
        String number = text(value, field);
        if (!number.matches("0|[1-9][0-9]*")) return -1;
        try { return Long.parseLong(number); }
        catch (NumberFormatException invalid) { return -1; }
    }

    private record Invocation(String runId, String invocationId) { }

    /** No mutable screenshot, model content or task completion status is part of this proof. */
    public record Frame(String runId, String invocationId, String sessionId, String targetId,
            String observationId, long windowGeneration, long contentRevision,
            long capturedAtMillis, long observedAtMillis, long sequence) { }
}
