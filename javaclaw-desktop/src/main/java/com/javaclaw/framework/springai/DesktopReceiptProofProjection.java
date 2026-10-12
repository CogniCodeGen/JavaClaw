package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.desktop.agent.DesktopSessionTools;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.util.SensitiveDataRedactor;

import java.util.Set;

/** 将可信桌面观察投影为条件与阶段证明，始终保留同帧和显式裁决绑定。 */
final class DesktopReceiptProofProjection {
    private DesktopReceiptProofProjection() { }

    static String conditionEvidence(DesktopSessionTools.ObservationProof observed) {
        if (observed.conditionEvidence().isEmpty() || observed.conditionEvidence().size() > 12
                || observed.conditionResults().isEmpty() || observed.conditionResults().size() > 12
                || observed.frameWidth() < 1 || observed.frameHeight() < 1) return "";
        var proof = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", 2).put("sessionId", observed.sessionId())
                .put("targetId", observed.targetId()).put("observationId", observed.observationId())
                .put("windowGeneration", observed.windowGeneration())
                .put("contentRevision", observed.contentRevision())
                .put("capturedAtMillis", observed.capturedAtMillis())
                .put("frameWidth", observed.frameWidth()).put("frameHeight", observed.frameHeight());
        var conditions = proof.putArray("conditions");
        for (var evidence : observed.conditionEvidence()) {
            if (evidence.criterionId() == null || evidence.criterionId().isBlank()
                    || evidence.subject() == null || evidence.subject().isBlank()
                    || !conditionConfidence(evidence.confidence())
                    || SensitiveDataRedactor.redactTextWithStatus(evidence.subject()).redacted()
                    || observed.conditionEvidence().stream().filter(other ->
                        evidence.criterionId().equals(other.criterionId())).count() != 1) continue;
            var decisions = observed.conditionResults().stream().filter(decision ->
                    evidence.criterionId().equals(decision.criterionId())).toList();
            if (decisions.size() != 1) continue;
            var decision = decisions.getFirst();
            var target = evidence.content();
            if (!evidence.subject().equals(decision.subject())
                    || decision.outcome() != com.javaclaw.agent.vision.DesktopVisualConditionResult.Outcome.TRUE
                    || !decision.complete() || !conditionConfidence(decision.confidence())
                    || !conditionContent(target, observed.frameWidth(), observed.frameHeight())
                    || !conditionContent(decision.content(), observed.frameWidth(), observed.frameHeight())
                    || !sameConditionContent(target, decision.content())) continue;
            var condition = conditions.addObject().put("criterionId", evidence.criterionId())
                    .put("subject", evidence.subject()).put("confidence", evidence.confidence())
                    .put("region", "main-content");
            condition.putObject("content").put("label", target.label()).put("role", target.role())
                    .put("x", target.x()).put("y", target.y()).put("width", target.width())
                    .put("height", target.height()).put("confidence", target.confidence());
            var accepted = condition.putObject("decision").put("outcome", decision.outcome().name())
                    .put("complete", decision.complete()).put("confidence", decision.confidence())
                    .put("region", "main-content");
            var content = decision.content();
            accepted.putObject("content").put("label", content.label()).put("role", content.role())
                    .put("x", content.x()).put("y", content.y()).put("width", content.width())
                    .put("height", content.height()).put("confidence", content.confidence());
        }
        String encoded = proof.toString();
        return conditions.isEmpty() || encoded.length() > EffectReceiptV1.MAX_CONDITION_EVIDENCE_CHARACTERS
                ? "" : encoded;
    }

    private static boolean conditionContent(com.javaclaw.agent.vision.DesktopVisualTarget content,
            int width, int height) {
        return content != null && content.label() != null && !content.label().isBlank()
                && content.label().length() <= 500 && content.role() != null
                && Set.of("content", "list", "table", "empty-state")
                    .contains(content.role().toLowerCase(java.util.Locale.ROOT))
                && !SensitiveDataRedactor.redactTextWithStatus(content.label()).redacted()
                && conditionConfidence(content.confidence())
                && content.x() >= 0 && content.y() >= 0 && content.width() > 0 && content.height() > 0
                && (long) content.x() + content.width() <= width
                && (long) content.y() + content.height() <= height;
    }

    private static boolean sameConditionContent(com.javaclaw.agent.vision.DesktopVisualTarget first,
            com.javaclaw.agent.vision.DesktopVisualTarget second) {
        return first.label().equals(second.label()) && first.role().equalsIgnoreCase(second.role())
                && first.x() == second.x() && first.y() == second.y()
                && first.width() == second.width() && first.height() == second.height();
    }

    private static boolean conditionConfidence(double confidence) {
        return Double.isFinite(confidence) && confidence >= 0.85 && confidence <= 1;
    }

    static String desktopStageEvidence(JsonNode raw, DesktopSessionTools.ObservationProof observed) {
        if (raw == null || !raw.isObject() || !"desktop.observation".equals(raw.path("kind").asText())
                || !"computer-use".equals(raw.path("protocol").asText())) return "";
        JsonNode stage = raw.path("interactionStage");
        if (!stage.isObject() || stage.path("schemaVersion").asInt() != 1
                || !"observation".equals(stage.path("kind").asText())
                || !"DESKTOP".equals(stage.path("mode").asText())
                || !stage.path("complete").isBoolean() || !stage.path("complete").booleanValue()
                || !stage.path("contractSequence").isIntegralNumber() || stage.path("contractSequence").asLong() < 1
                || !stage.path("contractSha256").asText().matches("[0-9a-f]{64}")
                || stage.path("runtimeId").asText().isBlank() || stage.path("surfaceId").asText().isBlank()
                || !observed.sessionId().equals(stage.path("contextId").asText())
                || !observed.sessionId().equals(raw.path("sessionId").asText())
                || !observed.targetId().equals(stage.path("targetId").asText())
                || !observed.targetId().equals(raw.path("targetId").asText())
                || !observed.applicationId().equals(stage.path("applicationId").asText())
                || !observed.applicationId().equals(raw.path("applicationId").asText())
                || !observed.observationId().equals(stage.path("observationId").asText())
                || !observed.observationId().equals(raw.path("observationId").asText())
                || observed.windowGeneration() != stage.path("generation").asLong(-1)
                || observed.windowGeneration() != raw.path("windowGeneration").asLong(-1)
                || observed.contentRevision() != stage.path("contentRevision").asLong(-1)
                || observed.contentRevision() != raw.path("contentRevision").asLong(-1)
                || observed.capturedAtMillis() != stage.path("capturedAtMillis").asLong(-1)
                || observed.capturedAtMillis() != raw.path("capturedAtMillis").asLong(-1)
                || observed.frameWidth() != stage.path("frameWidth").asInt(-1)
                || observed.frameHeight() != stage.path("frameHeight").asInt(-1)
                || !stage.path("conditions").isArray() || stage.path("conditions").isEmpty()
                || stage.path("conditions").size() > 12) return "";
        String value = stage.toString();
        return value.length() <= 32_000 ? value : "";
    }

    static String viewEvidence(com.javaclaw.agent.vision.DesktopVisualActiveView view) {
        if (view == null || view.heading() == null || view.content() == null) return "";
        return "heading:" + box(view.heading()) + "|content:" + box(view.content());
    }

    private static String box(com.javaclaw.agent.vision.DesktopVisualTarget target) {
        return target.x() + "," + target.y() + "," + target.width() + "," + target.height();
    }

}
