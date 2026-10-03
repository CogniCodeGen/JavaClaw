package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Validates criterion-specific content proof bound to the receipt's committed live frame. */
public final class DesktopConditionProof {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CONTENT_ROLES = Set.of("content", "list", "table", "empty-state");

    private DesktopConditionProof() { }

    public static boolean hasViewEvidence(String criterionId, String subject, JsonNode metadata) {
        return !metadata.path("viewEvidence").asText("").isBlank()
                || matches(criterionId, subject, metadata);
    }

    public static boolean matches(String criterionId, String subject, JsonNode metadata) {
        if (criterionId == null || criterionId.isBlank() || subject == null || subject.isBlank()) return false;
        String raw = metadata.path("conditionEvidence").asText("");
        if (raw.isBlank() || raw.length()
                > com.javaclaw.framework.spi.EffectReceiptV1.MAX_CONDITION_EVIDENCE_CHARACTERS) return false;
        try {
            JsonNode proof = JSON.readTree(raw);
            if (!proof.isObject() || proof.path("schemaVersion").asInt() != 1
                    || !proof.path("conditions").isArray() || proof.path("conditions").size() > 12
                    || !sameFrame(proof, metadata)) return false;
            JsonNode selected = null;
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (JsonNode condition : proof.path("conditions")) {
                String id = condition.path("criterionId").asText("");
                if (id.isBlank() || !ids.add(id)) return false;
                if (criterionId.equals(id)) selected = condition;
            }
            return selected != null
                    && subject.strip().equalsIgnoreCase(selected.path("subject").asText().strip())
                    && "main-content".equals(selected.path("region").asText())
                    && confidence(selected.path("confidence"))
                    && validContent(selected.path("content"), proof.path("frameWidth").asInt(),
                            proof.path("frameHeight").asInt());
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean sameFrame(JsonNode proof, JsonNode metadata) {
        String observation = metadata.path("observationId").asText("");
        UUID.fromString(observation);
        if (metadata.path("sessionId").asText("").isBlank()
                || metadata.path("targetId").asText("").isBlank()
                || !observation.equals(proof.path("observationId").asText())
                || !metadata.path("sessionId").asText().equals(proof.path("sessionId").asText())
                || !metadata.path("targetId").asText().equals(proof.path("targetId").asText())) return false;
        for (String field : new String[]{"windowGeneration", "contentRevision", "capturedAtMillis"}) {
            long expected = metadata.path(field).asLong(-1);
            if (expected < 0 || expected != proof.path(field).asLong(-2)) return false;
        }
        return metadata.path("capturedAtMillis").asLong() > 0
                && proof.path("frameWidth").isIntegralNumber() && proof.path("frameWidth").asInt() > 0
                && proof.path("frameHeight").isIntegralNumber() && proof.path("frameHeight").asInt() > 0;
    }

    private static boolean validContent(JsonNode content, int width, int height) {
        if (!content.isObject() || !content.path("label").isTextual()
                || content.path("label").asText().isBlank() || content.path("label").asText().length() > 500
                || !CONTENT_ROLES.contains(content.path("role").asText().toLowerCase(Locale.ROOT))
                || !confidence(content.path("confidence"))) return false;
        for (String field : new String[]{"x", "y", "width", "height"})
            if (!content.path(field).isIntegralNumber() || !content.path(field).canConvertToInt()) return false;
        int x = content.path("x").asInt(), y = content.path("y").asInt();
        int w = content.path("width").asInt(), h = content.path("height").asInt();
        return x >= 0 && y >= 0 && w > 0 && h > 0
                && (long) x + w <= width && (long) y + h <= height;
    }

    private static boolean confidence(JsonNode value) {
        double confidence = value.asDouble(Double.NaN);
        return value.isNumber() && Double.isFinite(confidence) && confidence >= 0.85 && confidence <= 1;
    }

}
