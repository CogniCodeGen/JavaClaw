package com.javaclaw.agent.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** 桌面视觉输出的有界候选 Schema，最终证据约束由宿主校验。 */
final class DesktopObservationSchema {
    private static final int MAX_VISUAL_TARGETS = 24;
    private static final int MAX_CONDITIONS = 12;
    private static final String CONFIDENCE_DESCRIPTION =
            "必须显式提供数字置信度；页面和条件证据至少为 0.85。无法确定时省略整项，宿主不会补值。";

    static JsonNode create() {
        ObjectNode schema = objectSchema();
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("summary").put("type", "string");
        properties.putObject("visibleText").put("type", "string")
                .put("description", "按阅读顺序保留画面原文；证据摘录必须逐字出现在这里。");
        ObjectNode targets = properties.putObject("targets");
        targets.put("type", "array").put("maxItems", MAX_VISUAL_TARGETS);
        targets.set("items", targetSchema(false));
        properties.set("activeView", activeViewSchema());
        ObjectNode conditions = properties.putObject("conditionEvidence");
        conditions.put("type", "array").put("maxItems", MAX_CONDITIONS);
        conditions.set("items", conditionSchema());
        ObjectNode results = properties.putObject("conditionResults");
        results.put("type", "array").put("maxItems", MAX_CONDITIONS);
        results.set("items", conditionResultSchema());
        schema.putArray("required").add("summary").add("visibleText").add("targets");
        return schema;
    }

    /** Only a targeted repair requires complete candidates; normal OCR still degrades safely. */
    static JsonNode confidenceRepair(List<DesktopObservationCondition> eligible) {
        ObjectNode schema = (ObjectNode) create();
        ObjectNode candidate = (ObjectNode) schema.path("properties")
                .path("conditionEvidence").path("items");
        candidate.withArray("required").add("confidence");
        ((ObjectNode) candidate.path("properties").path("content"))
                .withArray("required").add("confidence");
        var ids = ((ObjectNode) candidate.path("properties").path("criterionId")).putArray("enum");
        eligible.forEach(condition -> ids.add(condition.criterionId()));
        return schema;
    }

    /** A confidence-only repair need not regenerate already-accepted OCR or targets. */
    static JsonNode focusedConfidenceRepair(List<DesktopObservationCondition> eligible) {
        ObjectNode schema = objectSchema();
        schema.putObject("properties").set("conditionEvidence", confidenceRepair(eligible)
                .path("properties").path("conditionEvidence").deepCopy());
        schema.putArray("required").add("conditionEvidence");
        return schema;
    }

    private static ObjectNode targetSchema(boolean evidenceCandidate) {
        ObjectNode target = objectSchema();
        ObjectNode properties = target.putObject("properties");
        properties.putObject("label").put("type", "string")
                .put("maxLength", DesktopEvidenceExcerpt.MAX_CANDIDATE_LENGTH)
                .put("description", evidenceCandidate
                        ? "复制主区域连续的画面原文，建议不超过 240 字符，最多 500 字符；不要改写或复制完整表格。"
                        : "画面可见的控件标签。");
        properties.putObject("role").put("type", "string");
        for (String field : List.of("x", "y", "width", "height"))
            properties.putObject(field).put("type", "integer");
        properties.putObject("confidence").put("type", "number")
                .put("description", CONFIDENCE_DESCRIPTION);
        target.putArray("required").add("label").add("role").add("x").add("y")
                .add("width").add("height");
        if (!evidenceCandidate) target.withArray("required").add("confidence");
        return target;
    }

    private static ObjectNode activeViewSchema() {
        ObjectNode view = objectSchema();
        ObjectNode properties = view.putObject("properties");
        properties.putObject("label").put("type", "string");
        properties.set("heading", targetSchema(true));
        properties.set("content", targetSchema(true));
        properties.putObject("confidence").put("type", "number")
                .put("description", CONFIDENCE_DESCRIPTION);
        view.putArray("required").add("label").add("heading").add("content");
        candidateDescription(view);
        return view;
    }

    private static ObjectNode conditionSchema() {
        ObjectNode condition = objectSchema();
        ObjectNode properties = condition.putObject("properties");
        properties.putObject("criterionId").put("type", "string").put("minLength", 1).put("maxLength", 120)
                .put("description", "逐字复制 acceptanceConditions 的 criterionId。");
        properties.putObject("subject").put("type", "string").put("minLength", 1).put("maxLength", 240)
                .put("description", "逐字复制同一冻结条件的 subject，不能改写。");
        properties.putObject("region").put("type", "string")
                .put("description", "只能使用 main-content；侧栏、导航、标题栏、账号和输入区域不能提供证据。");
        ObjectNode content = targetSchema(true);
        ((ObjectNode) content.path("properties").path("role"))
                .put("description", "只能使用 content、list、table 或 empty-state。");
        properties.set("content", content);
        properties.putObject("confidence").put("type", "number")
                .put("description", CONFIDENCE_DESCRIPTION);
        condition.putArray("required").add("criterionId").add("subject").add("region").add("content");
        candidateDescription(condition);
        return condition;
    }

    private static ObjectNode conditionResultSchema() {
        ObjectNode result = objectSchema();
        ObjectNode properties = result.putObject("properties");
        properties.putObject("criterionId").put("type", "string").put("minLength", 1).put("maxLength", 120);
        properties.putObject("subject").put("type", "string").put("minLength", 1).put("maxLength", 240);
        properties.putObject("outcome").put("type", "string").putArray("enum")
                .add("TRUE").add("FALSE").add("UNKNOWN");
        properties.putObject("complete").put("type", "boolean");
        properties.putObject("contradiction").put("type", "boolean")
                .put("description", "仅当原帧主内容存在明确反证时为 true；未见或不清不能作为反证。");
        properties.putObject("region").put("type", "string").putArray("enum").add("main-content");
        properties.putObject("confidence").put("type", "number").put("description", CONFIDENCE_DESCRIPTION);
        properties.set("content", targetSchema(true));
        result.putArray("required").add("criterionId").add("subject").add("outcome").add("complete");
        return result;
    }

    private static void candidateDescription(ObjectNode schema) {
        // 可选证明字段缺失不能使有效 OCR 整体失败；宿主仍拒绝缺少 confidence 的证明。
        schema.put("description", "可选视觉候选，必须完整提供自身与子项 confidence；不合格候选由宿主逐项丢弃。");
    }

    private static ObjectNode objectSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object").put("additionalProperties", false);
        return schema;
    }

    private DesktopObservationSchema() { }
}
