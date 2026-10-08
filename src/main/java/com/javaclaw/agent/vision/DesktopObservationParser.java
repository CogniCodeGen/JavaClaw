package com.javaclaw.agent.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.util.SensitiveDataRedactor;

import java.util.ArrayList;
import java.util.List;

/** 宿主验证不可信视觉候选，仅将可定位的画面原文提升为证据。 */
final class DesktopObservationParser {
    private static final int MAX_VISUAL_TARGETS = 24;
    private static final int MAX_CONDITIONS = 12;
    private static final double MIN_TARGET_CONFIDENCE = 0.6;
    private static final double MIN_VIEW_CONFIDENCE = 0.85;

    static List<DesktopObservationCondition> boundedConditions(
            List<DesktopObservationCondition> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        if (requested.size() > MAX_CONDITIONS) return List.of();
        var identities = new java.util.HashSet<String>();
        for (var condition : requested) {
            if (condition == null || !identities.add(condition.criterionId())
                    || redactedVisualText(condition.subject())) return List.of();
        }
        return List.copyOf(requested);
    }

    static DesktopVisualObservation parse(JsonNode output, int frameWidth, int frameHeight,
                                                                    List<DesktopObservationCondition> conditions) {
        if (output == null || !output.isObject() || !output.path("summary").isTextual()
                || !output.path("visibleText").isTextual() || !output.path("targets").isArray()) return null;
        String summary = safeVisualText(output.path("summary").asText(), 1_000);
        String visibleText = safeVisualText(output.path("visibleText").asText(), 4_000);
        List<DesktopVisualTarget> targets = new ArrayList<>();
        for (JsonNode candidate : output.path("targets")) {
            if (targets.size() >= MAX_VISUAL_TARGETS) break;
            DesktopVisualTarget target = parseVisualTarget(candidate, frameWidth, frameHeight);
            if (target != null) targets.add(target);
        }
        if (summary.isBlank() && visibleText.isBlank() && targets.isEmpty()) return null;
        DesktopVisualActiveView activeView = parseActiveView(output.path("activeView"),
                visibleText, frameWidth, frameHeight);
        List<DesktopVisualConditionEvidence> positive = parseConditionEvidence(
                output.path("conditionEvidence"), output.path("conditionResults"), conditions,
                visibleText, frameWidth, frameHeight);
        return new DesktopVisualObservation(summary, visibleText, targets, activeView, positive,
                parseConditionResults(output.path("conditionResults"), conditions, positive,
                        visibleText, frameWidth, frameHeight));
    }

    static List<DesktopVisualConditionResult> unknownConditionResults(
            List<DesktopObservationCondition> conditions) {
        return conditions.stream().map(DesktopVisualConditionResult::unknown).toList();
    }

    static List<DesktopVisualConditionResult> parseConditionResults(JsonNode nodes,
            List<DesktopObservationCondition> conditions, List<DesktopVisualConditionEvidence> positive,
            String visibleText, int width, int height) {
        if (!nodes.isArray() || nodes.size() > MAX_CONDITIONS) return unknownConditionResults(conditions);
        List<DesktopVisualConditionResult> results = new ArrayList<>();
        for (DesktopObservationCondition condition : conditions) {
            DesktopVisualConditionResult accepted = DesktopVisualConditionResult.unknown(condition);
            JsonNode selected = null;
            int count = 0;
            for (JsonNode node : nodes) {
                if (condition.criterionId().equals(node.path("criterionId").asText())) {
                    selected = node;
                    count++;
                }
            }
            if (count == 1 && selected != null && selected.isObject()
                    && selected.path("subject").isTextual()
                    && condition.subject().equals(selected.path("subject").asText())
                    && selected.path("outcome").isTextual()
                    && selected.path("complete").isBoolean() && selected.path("complete").booleanValue()
                    && "main-content".equals(selected.path("region").asText())
                    && viewConfidence(selected.path("confidence"))) {
                String outcome = selected.path("outcome").asText();
                DesktopVisualTarget content = parseEvidenceTarget(selected.path("content"), visibleText, width, height);
                boolean supported = content != null && content.confidence() >= MIN_VIEW_CONFIDENCE
                        && !content.label().isBlank()
                        && List.of("content", "list", "table", "empty-state")
                                .contains(content.role().toLowerCase(java.util.Locale.ROOT))
                        && DesktopEvidenceExcerpt.containsVisibleText(visibleText, content.label());
                boolean hasPositive = positive.stream().anyMatch(evidence ->
                        condition.criterionId().equals(evidence.criterionId())
                                && condition.subject().equals(evidence.subject()));
                boolean trueDecision = "TRUE".equals(outcome) && hasPositive;
                boolean falseDecision = "FALSE".equals(outcome) && !hasPositive
                        && selected.path("contradiction").isBoolean()
                        && selected.path("contradiction").booleanValue();
                if (supported && (trueDecision || falseDecision)) {
                    accepted = new DesktopVisualConditionResult(condition.criterionId(), condition.subject(),
                            trueDecision ? DesktopVisualConditionResult.Outcome.TRUE
                                    : DesktopVisualConditionResult.Outcome.FALSE,
                            true, selected.path("confidence").doubleValue(), content);
                }
            }
            results.add(accepted);
        }
        return List.copyOf(results);
    }

    /** Diagnose only otherwise-valid exact host candidates, without inventing a confidence. */
    static ConfidenceRepair confidenceRepair(JsonNode output, String visibleText,
            int frameWidth, int frameHeight, List<DesktopObservationCondition> requested) {
        JsonNode nodes = output.path("conditionEvidence");
        if (requested.isEmpty() || !nodes.isArray() || nodes.size() > MAX_CONDITIONS)
            return new ConfidenceRepair(List.of(), List.of());
        var counts = new java.util.HashMap<String, Integer>();
        nodes.forEach(node -> counts.merge(node.path("criterionId").asText(), 1, Integer::sum));
        List<DesktopObservationCondition> eligible = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            if (!node.isObject() || !node.path("criterionId").isTextual()
                    || !node.path("subject").isTextual()
                    || !"main-content".equals(node.path("region").asText())) continue;
            String id = node.path("criterionId").asText();
            String subject = node.path("subject").asText();
            var condition = requested.stream().filter(value -> value.criterionId().equals(id)
                    && value.subject().equals(subject)).findFirst();
            if (condition.isEmpty() || counts.getOrDefault(id, 0) != 1) continue;
            JsonNode content = node.path("content");
            if (!content.isObject() || !content.path("label").isTextual()
                    || !content.path("role").isTextual()
                    || !List.of("content", "list", "table", "empty-state")
                            .contains(content.path("role").asText().toLowerCase(java.util.Locale.ROOT))
                    || redactedVisualText(content.path("label").asText())
                    || !validGeometry(content, frameWidth, frameHeight)) continue;
            String excerpt = DesktopEvidenceExcerpt.supportedExcerpt(content.path("label").asText(), visibleText);
            if (excerpt == null || excerpt.isBlank()
                    || !DesktopEvidenceExcerpt.containsVisibleText(visibleText, excerpt)
                    || !explicitTrueDecision(output.path("conditionResults"), id, subject,
                            content, visibleText, frameWidth, frameHeight)) continue;
            // Present null/string/low values are not missing fields and are never repaired here.
            if ((node.has("confidence") && !viewConfidence(node.path("confidence")))
                    || (content.has("confidence") && !viewConfidence(content.path("confidence")))) continue;
            if (node.has("confidence") && content.has("confidence")) continue;
            eligible.add(condition.get());
            if (!node.has("confidence")) paths.add("/output/conditionEvidence/" + index + "/confidence");
            if (!content.has("confidence"))
                paths.add("/output/conditionEvidence/" + index + "/content/confidence");
        }
        return new ConfidenceRepair(List.copyOf(eligible), List.copyOf(paths));
    }

    /** A repair without its original decision cannot establish a positive condition. */
    static List<DesktopVisualConditionEvidence> repairedConditions(JsonNode output, String visibleText,
            int frameWidth, int frameHeight, List<DesktopObservationCondition> eligible) {
        return List.of();
    }

    /** Only missing confidence values may change; original content and TRUE decisions stay frozen. */
    static List<DesktopVisualConditionEvidence> repairedConditions(JsonNode output, JsonNode original,
            String visibleText, int frameWidth, int frameHeight, List<DesktopObservationCondition> eligible) {
        if (!output.path("conditionEvidence").isArray()
                || output.path("conditionEvidence").size() > MAX_CONDITIONS
                || !original.path("conditionEvidence").isArray()) return List.of();
        var patched = new ArrayList<JsonNode>();
        for (var condition : eligible) {
            var originals = new ArrayList<JsonNode>();
            var corrections = new ArrayList<JsonNode>();
            original.path("conditionEvidence").forEach(node -> {
                if (condition.criterionId().equals(node.path("criterionId").asText())) originals.add(node);
            });
            output.path("conditionEvidence").forEach(node -> {
                if (condition.criterionId().equals(node.path("criterionId").asText())) corrections.add(node);
            });
            if (originals.size() != 1 || corrections.size() != 1
                    || !(originals.getFirst() instanceof ObjectNode before)
                    || !(corrections.getFirst() instanceof ObjectNode after)
                    || !(before.path("content") instanceof ObjectNode beforeContent)
                    || !(after.path("content") instanceof ObjectNode afterContent)) continue;
            ObjectNode originalShape = before.deepCopy(), correctedShape = after.deepCopy();
            originalShape.remove("confidence"); correctedShape.remove("confidence");
            ((ObjectNode) originalShape.path("content")).remove("confidence");
            ((ObjectNode) correctedShape.path("content")).remove("confidence");
            if (!originalShape.equals(correctedShape)
                    || !repairableConfidence(before, after)
                    || !repairableConfidence(beforeContent, afterContent)) continue;
            ObjectNode candidate = before.deepCopy();
            if (!before.has("confidence")) candidate.set("confidence", after.path("confidence").deepCopy());
            if (!beforeContent.has("confidence")) ((ObjectNode) candidate.path("content"))
                    .set("confidence", afterContent.path("confidence").deepCopy());
            patched.add(candidate);
        }
        var candidates = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        patched.forEach(candidates::add);
        return parseConditionEvidence(candidates, original.path("conditionResults"), eligible,
                visibleText, frameWidth, frameHeight);
    }

    private static boolean repairableConfidence(JsonNode original, JsonNode corrected) {
        return original.has("confidence") ? original.path("confidence").equals(corrected.path("confidence"))
                : viewConfidence(corrected.path("confidence"));
    }

    record ConfidenceRepair(List<DesktopObservationCondition> eligible, List<String> paths) { }

    private static List<DesktopVisualConditionEvidence> parseConditionEvidence(
            JsonNode nodes, JsonNode decisions, List<DesktopObservationCondition> requested,
            String visibleText, int frameWidth, int frameHeight) {
        if (!nodes.isArray() || nodes.size() > MAX_CONDITIONS || requested.isEmpty()) return List.of();
        var accepted = new java.util.LinkedHashMap<String, DesktopVisualConditionEvidence>();
        var duplicates = new java.util.HashSet<String>();
        for (JsonNode node : nodes) {
            if (!node.isObject() || !node.path("criterionId").isTextual()
                    || !node.path("subject").isTextual()
                    || !node.path("confidence").isNumber()
                    || !"main-content".equals(node.path("region").asText())) continue;
            String id = node.path("criterionId").asText();
            String subject = node.path("subject").asText();
            if (requested.stream().noneMatch(condition -> condition.criterionId().equals(id)
                    && condition.subject().equals(subject))) continue;
            double confidence = node.path("confidence").doubleValue();
            DesktopVisualTarget content = parseEvidenceTarget(node.path("content"), visibleText,
                    frameWidth, frameHeight);
            if (!Double.isFinite(confidence) || confidence < MIN_VIEW_CONFIDENCE || confidence > 1
                    || content == null || content.confidence() < MIN_VIEW_CONFIDENCE
                    || !List.of("content", "list", "table", "empty-state")
                            .contains(content.role().toLowerCase(java.util.Locale.ROOT))
                    || content.label().isBlank()
                    || redactedVisualText(node.path("content").path("label").asText())
                    || !DesktopEvidenceExcerpt.containsVisibleText(visibleText, content.label())
                    || !explicitTrueDecision(decisions, id, subject, node.path("content"),
                            visibleText, frameWidth, frameHeight)) continue;
            // Conflicting repeated proofs are ambiguous, so none may establish that condition.
            if (accepted.containsKey(id)) duplicates.add(id);
            else accepted.put(id, new DesktopVisualConditionEvidence(id, subject, content, confidence));
        }
        duplicates.forEach(accepted::remove);
        return List.copyOf(accepted.values());
    }

    private static boolean explicitTrueDecision(JsonNode decisions, String id, String subject,
            JsonNode evidenceContent, String visibleText, int width, int height) {
        if (!decisions.isArray() || decisions.size() > MAX_CONDITIONS) return false;
        JsonNode selected = null;
        int matches = 0;
        for (JsonNode node : decisions) {
            if (id.equals(node.path("criterionId").asText())) { selected = node; matches++; }
        }
        if (matches != 1 || selected == null || !selected.isObject()
                || !selected.path("subject").isTextual() || !subject.equals(selected.path("subject").asText())
                || !"TRUE".equals(selected.path("outcome").asText())
                || !selected.path("complete").isBoolean() || !selected.path("complete").booleanValue()
                || selected.path("contradiction").asBoolean(false)
                || !"main-content".equals(selected.path("region").asText())
                || !viewConfidence(selected.path("confidence"))) return false;
        DesktopVisualTarget content = parseEvidenceTarget(selected.path("content"), visibleText, width, height);
        if (content == null || content.confidence() < MIN_VIEW_CONFIDENCE
                || !List.of("content", "list", "table", "empty-state")
                        .contains(content.role().toLowerCase(java.util.Locale.ROOT))
                || !content.label().equals(DesktopEvidenceExcerpt.supportedExcerpt(
                        evidenceContent.path("label").asText(), visibleText))
                || !content.role().equalsIgnoreCase(evidenceContent.path("role").asText())) return false;
        return content.x() == evidenceContent.path("x").asInt(-1)
                && content.y() == evidenceContent.path("y").asInt(-1)
                && content.width() == evidenceContent.path("width").asInt(-1)
                && content.height() == evidenceContent.path("height").asInt(-1);
    }

    private static DesktopVisualActiveView parseActiveView(JsonNode node, String visibleText,
                                                             int frameWidth, int frameHeight) {
        if (!node.isObject() || !node.path("label").isTextual()
                || !node.path("confidence").isNumber()) return null;
        String label = safeVisualText(node.path("label").asText(), 120);
        double confidence = node.path("confidence").doubleValue();
        DesktopVisualTarget heading = parseVisualTarget(node.path("heading"), frameWidth, frameHeight);
        DesktopVisualTarget content = parseEvidenceTarget(node.path("content"), visibleText,
                frameWidth, frameHeight);
        if (label.isBlank() || redactedVisualText(node.path("label").asText())
                || !Double.isFinite(confidence) || confidence < MIN_VIEW_CONFIDENCE || confidence > 1
                || heading == null || content == null
                || heading.confidence() < MIN_VIEW_CONFIDENCE
                || content.confidence() < MIN_VIEW_CONFIDENCE
                || !List.of("heading", "title", "header")
                        .contains(heading.role().toLowerCase(java.util.Locale.ROOT))
                || !List.of("content", "list", "table", "empty-state")
                        .contains(content.role().toLowerCase(java.util.Locale.ROOT))
                || !label.equalsIgnoreCase(heading.label())
                || content.label().isBlank()
                || redactedVisualText(node.path("content").path("label").asText())
                || content.label().equalsIgnoreCase(label)
                || !DesktopEvidenceExcerpt.containsVisibleText(visibleText, heading.label())
                || !DesktopEvidenceExcerpt.containsVisibleText(visibleText, content.label())
                || content.y() < heading.y() + heading.height()
                || content.x() >= heading.x() + heading.width()
                || heading.x() >= content.x() + content.width()) return null;
        return new DesktopVisualActiveView(label, heading, content, confidence);
    }

    private static DesktopVisualTarget parseEvidenceTarget(JsonNode node, String visibleText,
                                                            int frameWidth, int frameHeight) {
        if (!node.isObject() || !node.path("label").isTextual()
                || redactedVisualText(node.path("label").asText())) return null;
        String excerpt = DesktopEvidenceExcerpt.supportedExcerpt(node.path("label").asText(), visibleText);
        if (excerpt == null) return null;
        // 只替换候选的文字摘录。身份、区域、角色、坐标与置信度仍须通过原有宿主校验。
        ObjectNode candidate = node.deepCopy();
        candidate.put("label", excerpt);
        return parseVisualTarget(candidate, frameWidth, frameHeight, 500);
    }

    private static DesktopVisualTarget parseVisualTarget(JsonNode node, int frameWidth, int frameHeight) {
        return parseVisualTarget(node, frameWidth, frameHeight, 120);
    }

    private static DesktopVisualTarget parseVisualTarget(JsonNode node, int frameWidth, int frameHeight,
                                                         int labelLimit) {
        if (!node.isObject() || !node.path("label").isTextual() || !node.path("role").isTextual()
                || !validGeometry(node, frameWidth, frameHeight)
                || !node.path("confidence").isNumber()) return null;
        int x = node.path("x").intValue();
        int y = node.path("y").intValue();
        int width = node.path("width").intValue();
        int height = node.path("height").intValue();
        double confidence = node.path("confidence").doubleValue();
        if (!Double.isFinite(confidence) || confidence < MIN_TARGET_CONFIDENCE || confidence > 1) return null;
        String label = safeVisualText(node.path("label").asText(), labelLimit);
        String role = safeVisualText(node.path("role").asText(), 40);
        if (label.isBlank() && role.isBlank()) return null;
        return new DesktopVisualTarget(label, role, x, y, width, height, confidence);
    }

    private static boolean validGeometry(JsonNode node, int frameWidth, int frameHeight) {
        if (!isInt(node.path("x")) || !isInt(node.path("y"))
                || !isInt(node.path("width")) || !isInt(node.path("height"))) return false;
        int x = node.path("x").intValue(), y = node.path("y").intValue();
        int width = node.path("width").intValue(), height = node.path("height").intValue();
        return x >= 0 && y >= 0 && width > 0 && height > 0
                && (long) x + width <= frameWidth && (long) y + height <= frameHeight;
    }

    private static boolean viewConfidence(JsonNode value) {
        double confidence = value.asDouble(Double.NaN);
        return value.isNumber() && Double.isFinite(confidence)
                && confidence >= MIN_VIEW_CONFIDENCE && confidence <= 1;
    }

    private static boolean isInt(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt();
    }

    private static String safeVisualText(String text, int maxCharacters) {
        String value = SensitiveDataRedactor.redactText(text == null ? "" : text.strip());
        return value.length() > maxCharacters ? value.substring(0, maxCharacters) : value;
    }

    private static boolean redactedVisualText(String text) {
        return SensitiveDataRedactor.redactTextWithStatus(
                text == null ? "" : text.strip()).redacted();
    }

    private DesktopObservationParser() { }
}
