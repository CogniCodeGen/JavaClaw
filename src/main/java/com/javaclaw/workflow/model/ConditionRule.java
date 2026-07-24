package com.javaclaw.workflow.model;

import com.fasterxml.jackson.databind.JsonNode;

/** 无脚本条件表达式。path 使用点分 JSON 路径。 */
public record ConditionRule(String path, ConditionOperator operator, JsonNode value) {
    public ConditionRule {
        path = path == null ? "" : path.trim();
        operator = operator == null ? ConditionOperator.EXISTS : operator;
        value = value == null ? null : value.deepCopy();
    }

    @Override public JsonNode value() { return value == null ? null : value.deepCopy(); }
}
