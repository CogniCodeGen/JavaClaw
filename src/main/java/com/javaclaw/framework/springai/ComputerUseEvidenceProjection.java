package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.api.ToolExecutionStatus;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Bounded model view of a host observation; the durable tool result is never changed. */
final class ComputerUseEvidenceProjection {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DISPLAY = "电脑观察结果；完整内容保存在执行日志。";

    private ComputerUseEvidenceProjection() { }

    /** Lossless wire compaction at the final provider boundary, never a host observation codec. */
    static List<Message> compactForModel(List<Message> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage response)) {
                result.add(message);
                continue;
            }
            List<ToolResponseMessage.ToolResponse> values = new ArrayList<>();
            boolean changed = false;
            for (var value : response.getResponses()) {
                String raw = value.responseData();
                String compact = compactObservation(value.name(), raw);
                changed |= !java.util.Objects.equals(raw, compact);
                values.add(new ToolResponseMessage.ToolResponse(value.id(), value.name(), compact));
            }
            if (!changed) {
                result.add(message);
                continue;
            }
            Message compact = ToolResponseMessage.builder().responses(values).metadata(response.getMetadata()).build();
            var host = HostContextBlock.metadata(message);
            if (host != null) {
                ObjectNode content = StepMessageCodec.message(compact);
                content.remove("hostContextBlock");
                compact = HostContextBlock.mark(compact, new HostContextBlock.Metadata(host.id(), host.kind(),
                        OnDemandContextSession.digest(content.toString()), host.scope(), host.required(), host.evidenceRefs()));
            }
            result.add(compact);
        }
        return List.copyOf(result);
    }

    private static String compactObservation(String toolName, String raw) {
        if (!"desktop_session_observe".equals(toolName) || raw == null) return raw;
        ObjectNode original = observationEnvelope(raw);
        if (original == null) return raw; // Includes an already-columnar provider snapshot.
        ObjectNode compact = original.deepCopy();
        ObjectNode data = (ObjectNode) compact.path("data");
        boolean changed = compactControls(data, "elements", "HOST_ACCESSIBILITY");
        changed |= compactControls(data, "visualTargets", "HOST_INTERPRETED_VISUAL");
        if (!changed) return raw;
        data.put("targetEncoding", "columns-v1: each rows entry follows columns exactly; full IDs and source order are unchanged");
        return compact.toString().length() < raw.length() ? compact.toString() : raw;
    }

    private static boolean compactControls(ObjectNode data, String field, String source) {
        if (!(data.path(field) instanceof ArrayNode values) || values.isEmpty()) return false;
        List<String> columns = new ArrayList<>();
        values.get(0).fieldNames().forEachRemaining(columns::add);
        // Uniform objects preserve absent-vs-null distinctions without adding presence masks.
        if (columns.isEmpty()) return false;
        for (JsonNode value : values) {
            if (!value.isObject() || value.size() != columns.size()
                    || columns.stream().anyMatch(name -> !value.has(name))) return false;
        }
        ObjectNode table = JSON.createObjectNode().put("source", source);
        ArrayNode names = table.putArray("columns");
        columns.forEach(names::add);
        ArrayNode rows = table.putArray("rows");
        for (JsonNode value : values) {
            ArrayNode row = rows.addArray();
            columns.forEach(name -> row.add(value.get(name).deepCopy()));
        }
        if (table.toString().length() >= values.toString().length()) return false;
        data.set(field, table);
        return true;
    }

    static List<Message> project(List<Message> exchange, int maxResultCharacters) {
        if (maxResultCharacters < 1) throw new IllegalArgumentException("result budget must be positive");
        List<Message> result = new ArrayList<>(exchange.size());
        for (Message message : exchange) {
            if (!(message instanceof ToolResponseMessage response)) {
                result.add(message);
                continue;
            }
            List<ToolResponseMessage.ToolResponse> values = new ArrayList<>();
            boolean changed = false;
            for (var value : response.getResponses()) {
                String projected = projectObservation(value.name(), value.responseData(), maxResultCharacters);
                changed |= !java.util.Objects.equals(projected, value.responseData());
                values.add(new ToolResponseMessage.ToolResponse(value.id(), value.name(), projected));
            }
            result.add(changed ? ToolResponseMessage.builder().responses(values)
                    .metadata(response.getMetadata()).build() : response);
        }
        return List.copyOf(result);
    }

    private static String projectObservation(String toolName, String raw, int limit) {
        if (!"desktop_session_observe".equals(toolName) || raw == null) return raw;
        ObjectNode sourceEnvelope = observationEnvelope(raw);
        if (sourceEnvelope == null) return raw;
        ObjectNode envelope = sourceEnvelope.deepCopy();
        ObjectNode data = (ObjectNode) envelope.path("data");
        envelope.put("displayMessage", DISPLAY);
        if (fits(envelope, data, limit)) return envelope.toString();

        ObjectNode original = (ObjectNode) sourceEnvelope.path("data");
        ArrayNode sourceElements = (ArrayNode) original.path("elements");
        ArrayNode sourceVisual = (ArrayNode) original.path("visualTargets");
        ObjectNode content = (ObjectNode) data.path("content");
        String summary = content.path("summary").asText("");
        String visibleText = content.path("visibleText").asText("");
        content.put("summary", "").put("visibleText", "");
        ArrayNode elements = data.putArray("elements");
        ArrayNode visual = data.putArray("visualTargets");
        updateTruncation(data, original, summary, visibleText);
        if (!fits(envelope, data, limit)) {
            omitConditionExcerpts(data);
        }
        if (!fits(envelope, data, limit)) {
            throw irreducible(envelope, data, limit);
        }

        // Preserve room for actionable controls as well as screen text. JSON escaping
        // can cost more than the text length, so admission below uses the actual wire.
        long available = Math.min((long) limit - data.toString().length(),
                (long) limit + 512 - envelope.toString().length());
        int textBudget = (int) Math.max(0, Math.min(8_000, available / 3));
        int summaryBudget = Math.min(2_000, textBudget / 3);
        content.put("summary", prefix(summary, summaryBudget));
        content.put("visibleText", prefix(visibleText, textBudget - summaryBudget));
        updateTruncation(data, original, summary, visibleText);
        while (!fits(envelope, data, limit)) {
            String currentSummary = content.path("summary").asText();
            String currentText = content.path("visibleText").asText();
            if (currentSummary.isEmpty() && currentText.isEmpty()) {
                throw irreducible(envelope, data, limit);
            }
            String field = currentText.length() >= currentSummary.length() ? "visibleText" : "summary";
            String current = content.path(field).asText();
            content.put(field, prefix(current, current.length() / 2));
            updateTruncation(data, original, summary, visibleText);
        }

        boolean[] keptElements = new boolean[sourceElements.size()];
        boolean[] keptVisual = new boolean[sourceVisual.size()];
        for (Candidate candidate : candidates(sourceElements, sourceVisual)) {
            ArrayNode destination = candidate.visual() ? visual : elements;
            destination.add(candidate.value());
            updateTruncation(data, original, summary, visibleText);
            if (fits(envelope, data, limit)) {
                (candidate.visual() ? keptVisual : keptElements)[candidate.index()] = true;
            } else {
                destination.remove(destination.size() - 1);
                updateTruncation(data, original, summary, visibleText);
            }
        }
        // Selection favors actionable controls; presentation retains each source array's order.
        restoreOrder(elements, sourceElements, keptElements);
        restoreOrder(visual, sourceVisual, keptVisual);
        updateTruncation(data, original, summary, visibleText);
        return envelope.toString();
    }

    /**
     * Literal proof text is a display field in the provider view. The verifier reads
     * the full durable host receipt; identities, frame binding and geometry stay exact.
     */
    private static void omitConditionExcerpts(ObjectNode data) {
        if (!(data.path("conditionEvidence") instanceof ArrayNode conditions)) return;
        int omitted = 0;
        for (JsonNode condition : conditions) {
            if (!(condition.path("content") instanceof ObjectNode content)) continue;
            if (content.path("label").isTextual()) {
                content.remove("label");
                content.put("excerptOmitted", true);
            }
            if (content.path("excerptOmitted").asBoolean(false)) omitted++;
        }
        if (omitted > 0) {
            data.put("conditionEvidenceProjectionTruncated", true);
            data.put("conditionEvidenceOmittedExcerptCount", omitted);
        }
    }

    private static ObjectNode observationEnvelope(String raw) {
        try {
            JsonNode parsed = JSON.readTree(raw);
            if (!(parsed instanceof ObjectNode envelope) || !envelope.path("status").isTextual()) return null;
            ToolExecutionStatus.valueOf(envelope.path("status").asText());
            if (!(envelope.path("data") instanceof ObjectNode data)
                    || !data.path("schemaVersion").isIntegralNumber()
                    || data.path("schemaVersion").asInt(-1) != 1
                    || !"computer-use".equals(data.path("protocol").asText())
                    || !"desktop.observation".equals(data.path("kind").asText())
                    || !identity(data, "sessionId") || !identity(data, "targetId")
                    || !identity(data, "observationId")
                    || !data.path("windowGeneration").isIntegralNumber()
                    || !data.path("contentRevision").isIntegralNumber()
                    || !(data.path("frame") instanceof ObjectNode frame)
                    || !data.path("targetId").equals(frame.path("targetId"))
                    || !data.path("windowGeneration").equals(frame.path("windowGeneration"))
                    || !data.path("contentRevision").equals(frame.path("contentRevision"))
                    || !frame.path("width").isIntegralNumber() || frame.path("width").asInt() < 1
                    || !frame.path("height").isIntegralNumber() || frame.path("height").asInt() < 1
                    || !(data.path("content") instanceof ObjectNode content)
                    || !content.path("summary").isTextual() || !content.path("visibleText").isTextual()
                    || !(data.path("elements") instanceof ArrayNode)
                    || !(data.path("visualTargets") instanceof ArrayNode)
                    || !validControls(data.path("elements"))
                    || !validControls(data.path("visualTargets"))) return null;
            return envelope;
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean identity(ObjectNode data, String name) {
        return data.path(name).isTextual() && !data.path(name).asText().isBlank();
    }

    private static boolean validControls(JsonNode values) {
        for (JsonNode value : values) {
            if (!(value instanceof ObjectNode entry) || !identity(entry, "id")) return false;
        }
        return true;
    }

    private static boolean fits(ObjectNode envelope, ObjectNode data, int limit) {
        return data.toString().length() <= limit
                && (long) envelope.toString().length() <= (long) limit + 512;
    }

    private static void updateTruncation(ObjectNode data, ObjectNode original,
            String summary, String visibleText) {
        int elements = data.path("elements").size();
        int visual = data.path("visualTargets").size();
        boolean textTruncated = !summary.equals(data.path("content").path("summary").asText())
                || !visibleText.equals(data.path("content").path("visibleText").asText());
        data.put("projectionTruncated", true);
        data.put("projectedElementCount", elements).put("projectedVisualTargetCount", visual);
        data.put("projectionOmittedElementCount", original.path("elements").size() - elements);
        data.put("projectionOmittedVisualTargetCount", original.path("visualTargets").size() - visual);
        data.put("elementsTruncated", original.path("elementsTruncated").asBoolean()
                || elements < original.path("elements").size());
        data.put("visualTargetsTruncated", original.path("visualTargetsTruncated").asBoolean()
                || visual < original.path("visualTargets").size());
        ((ObjectNode) data.path("content")).put("truncated",
                original.path("content").path("truncated").asBoolean() || textTruncated)
                .put("projectionTruncated", textTruncated);
    }

    private static List<Candidate> candidates(ArrayNode elements, ArrayNode visual) {
        List<Candidate> accessible = candidates(elements, false);
        List<Candidate> regions = candidates(visual, true);
        List<Candidate> result = new ArrayList<>(accessible.size() + regions.size());
        for (int index = 0; index < Math.max(accessible.size(), regions.size()); index++) {
            if (index < accessible.size()) result.add(accessible.get(index));
            if (index < regions.size()) result.add(regions.get(index));
        }
        return result;
    }

    private static List<Candidate> candidates(ArrayNode source, boolean visual) {
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            candidates.add(new Candidate(visual, index, source.get(index)));
        }
        candidates.sort(Comparator.comparing(candidate -> !candidate.value().path("pressable").asBoolean()));
        return candidates;
    }

    private static void restoreOrder(ArrayNode destination, ArrayNode original, boolean[] kept) {
        destination.removeAll();
        for (int index = 0; index < kept.length; index++) {
            if (kept[index]) destination.add(original.get(index));
        }
    }

    private static String prefix(String text, int limit) {
        int end = Math.min(text.length(), Math.max(0, limit));
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static LocalContextBudgetExceededException irreducible(
            ObjectNode envelope, ObjectNode data, int limit) {
        int payload = data.toString().length();
        int metadata = envelope.toString().length() - payload;
        List<String> refs = new ArrayList<>();
        envelope.path("evidenceRefs").forEach(ref -> { if (ref.isTextual()) refs.add(ref.asText()); });
        return new LocalContextBudgetExceededException("computer_use_observation_identity", limit,
                payload + metadata, Map.of("observation_identity", payload, "envelope_metadata", metadata), refs);
    }

    private record Candidate(boolean visual, int index, JsonNode value) { }
}
