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
        return new DesktopVisualObservation(summary, visibleText, targets, activeView,
                parseConditionEvidence(output.path("conditionEvidence"), conditions,
                        visibleText, frameWidth, frameHeight));
    }

    private static List<DesktopVisualConditionEvidence> parseConditionEvidence(
            JsonNode nodes, List<DesktopObservationCondition> requested,
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
                    || !DesktopEvidenceExcerpt.containsVisibleText(visibleText, content.label())) continue;
            // Conflicting repeated proofs are ambiguous, so none may establish that condition.
            if (accepted.containsKey(id)) duplicates.add(id);
            else accepted.put(id, new DesktopVisualConditionEvidence(id, subject, content, confidence));
        }
        duplicates.forEach(accepted::remove);
        return List.copyOf(accepted.values());
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
                || !isInt(node.path("x")) || !isInt(node.path("y"))
                || !isInt(node.path("width")) || !isInt(node.path("height"))
                || !node.path("confidence").isNumber()) return null;
        int x = node.path("x").intValue();
        int y = node.path("y").intValue();
        int width = node.path("width").intValue();
        int height = node.path("height").intValue();
        double confidence = node.path("confidence").doubleValue();
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || (long) x + width > frameWidth || (long) y + height > frameHeight
                || !Double.isFinite(confidence) || confidence < MIN_TARGET_CONFIDENCE || confidence > 1) return null;
        String label = safeVisualText(node.path("label").asText(), labelLimit);
        String role = safeVisualText(node.path("role").asText(), 40);
        if (label.isBlank() && role.isBlank()) return null;
        return new DesktopVisualTarget(label, role, x, y, width, height, confidence);
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
