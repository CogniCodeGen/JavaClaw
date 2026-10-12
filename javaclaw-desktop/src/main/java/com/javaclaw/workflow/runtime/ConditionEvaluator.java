package com.javaclaw.workflow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.workflow.model.ConditionRule;
import com.javaclaw.workflow.model.GraphState;

/** 受限条件求值器，不解释任何脚本。 */
public final class ConditionEvaluator {
    private ConditionEvaluator() {}

    public static boolean matches(GraphState state, ConditionRule rule) {
        if (rule == null) return false;
        JsonNode actual = state.get(rule.path());
        JsonNode expected = rule.value();
        return switch (rule.operator()) {
            case EXISTS -> !actual.isMissingNode() && !actual.isNull();
            case EQUAL -> !actual.isMissingNode() && actual.equals(expected);
            case NOT_EQUAL -> actual.isMissingNode() || !actual.equals(expected);
            case CONTAINS -> contains(actual, expected);
            case GT -> numeric(actual, expected) && compareNumber(actual, expected) > 0;
            case GTE -> numeric(actual, expected) && compareNumber(actual, expected) >= 0;
            case LT -> numeric(actual, expected) && compareNumber(actual, expected) < 0;
            case LTE -> numeric(actual, expected) && compareNumber(actual, expected) <= 0;
        };
    }

    private static boolean contains(JsonNode actual, JsonNode expected) {
        if (actual.isTextual()) return expected != null && actual.asText().contains(expected.asText());
        if (actual.isArray()) {
            for (JsonNode item : actual) if (item.equals(expected)) return true;
        }
        return false;
    }

    private static int compareNumber(JsonNode actual, JsonNode expected) {
        return actual.decimalValue().compareTo(expected.decimalValue());
    }

    private static boolean numeric(JsonNode actual, JsonNode expected) {
        return actual != null && expected != null && actual.isNumber() && expected.isNumber();
    }
}
