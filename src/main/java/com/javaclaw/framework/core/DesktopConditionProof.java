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
            if (!proof.isObject() || !proof.path("schemaVersion").isInt() || proof.path("schemaVersion").intValue() != 2
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
                            proof.path("frameHeight").asInt())
                    && validDecision(selected.path("decision"), selected.path("content"),
                            proof.path("frameWidth").asInt(), proof.path("frameHeight").asInt());
        } catch (Exception invalid) {
            return false;
        }
    }

    /** Only the existing-stage revalidator may use this temporary view; ordinary acceptance remains v2-only. */
    static JsonNode persistedStageMetadata(String criterionId, String subject, JsonNode metadata,
            JsonNode rawObservation, JsonNode stage) {
        try {
            if (criterionId == null || criterionId.isBlank() || subject == null || subject.isBlank()
                    || !metadata.isObject() || !rawObservation.isObject() || !stage.isObject()
                    || !stage.equals(rawObservation.path("interactionStage"))) return null;
            String encoded = metadata.path("conditionEvidence").asText("");
            if (encoded.isBlank() || encoded.length()
                    > com.javaclaw.framework.spi.EffectReceiptV1.MAX_CONDITION_EVIDENCE_CHARACTERS) return null;
            JsonNode old = JSON.readTree(encoded);
            if (!old.isObject() || !old.path("schemaVersion").isInt() || old.path("schemaVersion").intValue() != 1
                    || !old.path("conditions").isArray() || old.path("conditions").size() > 12
                    || !sameFrame(old, metadata)
                    || !old.path("sessionId").asText().equals(stage.path("contextId").asText())
                    || !old.path("targetId").asText().equals(stage.path("targetId").asText())
                    || !old.path("observationId").asText().equals(stage.path("observationId").asText())
                    || old.path("windowGeneration").asLong(-1) != stage.path("generation").asLong(-2)) return null;
            for (String field : new String[]{"contentRevision", "capturedAtMillis", "frameWidth", "frameHeight"})
                if (!old.path(field).isIntegralNumber() || !stage.path(field).isIntegralNumber()
                        || old.path(field).longValue() != stage.path(field).longValue()) return null;
            JsonNode selected = null, decision = null, rawPositive = null;
            Set<String> ids = new java.util.HashSet<>();
            for (JsonNode condition : old.path("conditions")) {
                String id = condition.path("criterionId").asText();
                if (id.isBlank() || !ids.add(id)) return null;
                if (criterionId.equals(id)) selected = condition;
            }
            if (!stage.path("conditions").isArray() || !rawObservation.path("conditionEvidence").isArray()) return null;
            for (JsonNode condition : stage.path("conditions")) if (criterionId.equals(condition.path("criterionId").asText())) {
                if (decision != null) return null;
                decision = condition;
            }
            for (JsonNode condition : rawObservation.path("conditionEvidence")) if (criterionId.equals(condition.path("criterionId").asText())) {
                if (rawPositive != null) return null;
                rawPositive = condition;
            }
            if (selected == null || decision == null || rawPositive == null
                    || !subject.equals(selected.path("subject").asText())
                    || !"main-content".equals(selected.path("region").asText())
                    || !confidence(selected.path("confidence")) || !confidence(decision.path("confidence"))
                    || !"TRUE".equals(decision.path("outcome").asText())
                    || !decision.path("complete").isBoolean() || !decision.path("complete").booleanValue()
                    || decision.path("contradiction").isBoolean() && decision.path("contradiction").booleanValue()
                    || !"main-content".equals(decision.path("evidence").path("region").asText())
                    || !selected.path("confidence").equals(decision.path("confidence"))) return null;
            for (String field : new String[]{"criterionId", "subject", "region", "confidence", "content"})
                if (!selected.path(field).equals(rawPositive.path(field))) return null;
            JsonNode content = selected.path("content"), acceptedContent = decision.path("evidence");
            int width = old.path("frameWidth").asInt(), height = old.path("frameHeight").asInt();
            if (!validContent(content, width, height) || !validContent(acceptedContent, width, height)
                    || com.javaclaw.util.SensitiveDataRedactor.redactTextWithStatus(subject).redacted()
                    || com.javaclaw.util.SensitiveDataRedactor.redactTextWithStatus(content.path("label").asText()).redacted()) return null;
            for (String field : new String[]{"label", "role", "x", "y", "width", "height", "confidence"})
                if (!content.path(field).equals(acceptedContent.path(field))) return null;
            var converted = (com.fasterxml.jackson.databind.node.ObjectNode) old.deepCopy();
            converted.put("schemaVersion", 2);
            var condition = (com.fasterxml.jackson.databind.node.ObjectNode) selected.deepCopy();
            condition.putObject("decision").put("outcome", "TRUE").put("complete", true)
                    .put("region", "main-content").set("confidence", decision.path("confidence"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) condition.path("decision"))
                    .set("content", content.deepCopy());
            converted.putArray("conditions").add(condition);
            String proof = converted.toString();
            if (proof.length() > com.javaclaw.framework.spi.EffectReceiptV1.MAX_CONDITION_EVIDENCE_CHARACTERS) return null;
            var view = (com.fasterxml.jackson.databind.node.ObjectNode) metadata.deepCopy();
            view.put("conditionEvidence", proof);
            return matches(criterionId, subject, view) ? view : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    private static boolean validDecision(JsonNode decision, JsonNode evidence, int width, int height) {
        if (!decision.isObject() || !"TRUE".equals(decision.path("outcome").asText())
                || !decision.path("complete").isBoolean() || !decision.path("complete").booleanValue()
                || !"main-content".equals(decision.path("region").asText())
                || !confidence(decision.path("confidence"))
                || !validContent(decision.path("content"), width, height)) return false;
        JsonNode content = decision.path("content");
        if (!evidence.path("label").equals(content.path("label"))
                || !evidence.path("role").asText().equalsIgnoreCase(content.path("role").asText())) return false;
        for (String field : new String[]{"x", "y", "width", "height"})
            if (evidence.path(field).intValue() != content.path(field).intValue()) return false;
        return true;
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
