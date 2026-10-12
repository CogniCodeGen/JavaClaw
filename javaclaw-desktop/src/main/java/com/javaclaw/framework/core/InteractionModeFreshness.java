package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.api.InteractionMode;
import com.javaclaw.framework.api.RunEventEnvelope;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.InteractionStageContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Fresh observation admission only: this never proves a business condition or clears an effect. */
public final class InteractionModeFreshness {
    private InteractionModeFreshness() { }

    public static Optional<DesktopObservationBaseline.Frame> latestDesktopBaseline(
            RunId run, List<RunEventEnvelope> history) {
        return desktopBaselines(run, history).stream()
                .max(Comparator.comparingLong(DesktopObservationBaseline.Frame::sequence));
    }

    public static List<DesktopObservationBaseline.Frame> desktopBaselines(
            RunId run, List<RunEventEnvelope> history) {
        List<RunEventEnvelope> events = own(run, history);
        Boundary boundary = boundary(events);
        if (boundary == null) return List.of();
        Map<String, Triple> triples = triples(events, boundary);
        return DesktopObservationBaseline.fromEvents(events).stream().filter(frame -> {
            Triple triple = triples.get(frame.invocationId());
            return triple != null && triple.tool().equals("desktop_session_observe")
                    && frame.sequence() == triple.receipt().sequence()
                    && frame.capturedAtMillis() >= boundary.at().toEpochMilli();
        }).toList();
    }

    static boolean observed(RunId run, List<RunEventEnvelope> history,
            InteractionMode mode, ObjectMapper json) {
        if (mode == InteractionMode.DESKTOP) return latestDesktopBaseline(run, history).isPresent();
        if (mode != InteractionMode.BROWSER) return false;
        List<RunEventEnvelope> events = own(run, history);
        Boundary boundary = boundary(events);
        if (boundary == null) return false;
        for (Triple triple : triples(events, boundary).values()) {
            if (!Set.of("web_snapshot", "web_get_text").contains(triple.tool())) continue;
            JsonNode metadata = triple.receipt().payload().path("metadata");
            String encoded = metadata.path("interactionStage").asText("");
            if (encoded.isBlank() || encoded.length() > 32_000) continue;
            try {
                JsonNode proof = json.readTree(encoded);
                if (proof == null || !proof.isObject()
                        || !proof.path("schemaVersion").isIntegralNumber() || proof.path("schemaVersion").asInt() != 1
                        || !proof.path("kind").asText().equals("observation")
                        || !proof.path("mode").asText().equals("BROWSER")
                        || !proof.path("complete").isBoolean() || !proof.path("complete").booleanValue()
                        || !proof.path("scope").asText().equals("complete-visible-body")
                        || !proof.path("contractSequence").isIntegralNumber()
                        || proof.path("contractSequence").asLong() != boundary.contract().sequence()
                        || !proof.path("contractSha256").asText().equals(
                                InteractionStageContext.sha256(boundary.contract().payload().toString()))
                        || !proof.path("bodySha256").asText().matches("[0-9a-f]{64}")
                        || !proof.path("urlSha256").asText().matches("[0-9a-f]{64}")
                        || !proof.path("urlSha256").asText().equals(metadata.path("browserUrlSha256").asText())
                        || !metadata.path("browserUrlFormat").asText().equals("canonical-http-url-sha256-v1")
                        || !proof.path("generation").isIntegralNumber() || proof.path("generation").asLong() < 1
                        || List.of("runtimeId", "contextId", "surfaceId", "documentId", "observationId")
                            .stream().anyMatch(key -> !identifier(proof.path(key)))
                        || !proof.path("capturedAtMillis").isIntegralNumber()) continue;
                long capture = proof.path("capturedAtMillis").asLong();
                if (capture >= boundary.at().toEpochMilli()
                        && capture >= triple.start().timestamp().toEpochMilli()
                        && capture <= triple.complete().timestamp().toEpochMilli()) return true;
            } catch (RuntimeException | java.io.IOException invalid) { /* Missing proof grants no transition. */ }
        }
        return false;
    }

    private static List<RunEventEnvelope> own(RunId run, List<RunEventEnvelope> history) {
        if (run == null || history == null) return List.of();
        return history.stream().filter(event -> event != null && event.runId().equals(run.value())
                && event.producer().equals("framework.core")).toList();
    }

    private static Boundary boundary(List<RunEventEnvelope> events) {
        RunEventEnvelope contract = events.stream().filter(event -> event.schemaVersion() == 3
                && Set.of("core.task.contract", "core.task.contract_revised").contains(event.type())
                && event.payload().path("version").asInt() == 3)
                .max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        if (contract == null) return null;
        RunEventEnvelope mode = events.stream().filter(event -> event.schemaVersion() == 1
                && event.type().equals(InteractionExecutionPolicy.MODE_SELECTED_EVENT))
                .max(Comparator.comparingLong(RunEventEnvelope::sequence)).orElse(null);
        long sequence = Math.max(contract.sequence(), mode == null ? 0 : mode.sequence());
        Instant at = mode != null && mode.timestamp().isAfter(contract.timestamp()) ? mode.timestamp() : contract.timestamp();
        return new Boundary(contract, sequence, at);
    }

    private static Map<String, Triple> triples(List<RunEventEnvelope> events, Boundary boundary) {
        Map<String, List<RunEventEnvelope>> groups = new LinkedHashMap<>();
        for (RunEventEnvelope event : events) {
            if (!Set.of("core.tool.started", "core.tool.completed", "core.tool.receipt").contains(event.type())) continue;
            String id = event.payload().path("invocationId").asText("");
            if (!id.isBlank()) groups.computeIfAbsent(id, ignored -> new ArrayList<>()).add(event);
        }
        Map<String, Triple> result = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            List<RunEventEnvelope> stages = entry.getValue();
            if (stages.size() != 3) continue;
            RunEventEnvelope start = unique(stages, "core.tool.started", 1);
            RunEventEnvelope complete = unique(stages, "core.tool.completed", 2);
            RunEventEnvelope receipt = unique(stages, "core.tool.receipt", 1);
            if (start == null || complete == null || receipt == null || start.sequence() <= boundary.sequence()
                    || start.sequence() >= complete.sequence() || complete.sequence() >= receipt.sequence()
                    || start.timestamp().isBefore(boundary.at()) || start.timestamp().isAfter(complete.timestamp())
                    || complete.timestamp().isAfter(receipt.timestamp())
                    || !start.payload().path("trustedDesktopTool").isBoolean()
                    || !start.payload().path("trustedDesktopTool").booleanValue()
                    || !complete.payload().path("status").asText().equals("SUCCEEDED")
                    || !complete.payload().path("errorCode").asText("").isBlank()
                    || !receipt.payload().path("status").asText().equals("OBSERVED")
                    || !receipt.payload().path("operation").asText().equals("observe")) continue;
            String tool = start.payload().path("tool").asText();
            if (!tool.equals(complete.payload().path("tool").asText())
                    || !tool.equals(receipt.payload().path("tool").asText())
                    || !receipt.payload().path("evidenceRef").asText().equals("core.tool.completed:"
                            + start.runId() + ":" + entry.getKey())
                    || !start.payload().path("arguments").isObject()) continue;
            String fingerprint = ToolInvocationFingerprint.create(tool, start.payload().path("arguments"));
            if (!fingerprint.equals(start.payload().path("fingerprint").asText())
                    || !fingerprint.equals(receipt.payload().path("fingerprint").asText())) continue;
            try {
                Instant observed = Instant.parse(receipt.payload().path("observedAt").asText());
                if (observed.isBefore(start.timestamp()) || observed.isAfter(complete.timestamp())) continue;
            } catch (RuntimeException invalid) { continue; }
            result.put(entry.getKey(), new Triple(tool, start, complete, receipt));
        }
        return result;
    }

    private static RunEventEnvelope unique(List<RunEventEnvelope> events, String type, int schema) {
        List<RunEventEnvelope> matching = events.stream().filter(event -> event.type().equals(type)
                && event.schemaVersion() == schema).toList();
        return matching.size() == 1 ? matching.getFirst() : null;
    }

    private static boolean identifier(JsonNode value) {
        if (!value.isTextual()) return false;
        String text = value.asText();
        return !text.isBlank() && text.length() <= 512 && text.equals(text.strip())
                && text.codePoints().noneMatch(Character::isISOControl);
    }

    private record Boundary(RunEventEnvelope contract, long sequence, Instant at) { }
    private record Triple(String tool, RunEventEnvelope start, RunEventEnvelope complete, RunEventEnvelope receipt) { }
}
