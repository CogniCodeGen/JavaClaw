package com.javaclaw.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.workflow.model.ConditionOperator;
import com.javaclaw.workflow.model.ConditionRule;
import com.javaclaw.workflow.model.GraphState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionEvaluatorBehaviorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void existenceAndEqualityTreatMissingNullAndValuesDifferently() {
        GraphState state = state("""
                {"present":"value","nothing":null,"number":3}
                """);

        assertFalse(ConditionEvaluator.matches(state, null));
        assertTrue(matches(state, "present", ConditionOperator.EXISTS, null));
        assertFalse(matches(state, "nothing", ConditionOperator.EXISTS, null));
        assertFalse(matches(state, "missing", ConditionOperator.EXISTS, null));

        assertTrue(matches(state, "present", ConditionOperator.EQUAL, text("value")));
        assertFalse(matches(state, "present", ConditionOperator.EQUAL, text("other")));
        assertFalse(matches(state, "missing", ConditionOperator.EQUAL, null));
        assertFalse(matches(state, "missing", ConditionOperator.EQUAL, text("value")));

        assertFalse(matches(state, "present", ConditionOperator.NOT_EQUAL, text("value")));
        assertTrue(matches(state, "present", ConditionOperator.NOT_EQUAL, text("other")));
        assertTrue(matches(state, "missing", ConditionOperator.NOT_EQUAL, text("value")));
    }

    @Test
    void containsSupportsTextAndArrayButRejectsOtherShapes() {
        GraphState state = state("""
                {"text":"JavaClaw desktop", "items":["alpha",2], "object":{"a":1}}
                """);

        assertTrue(matches(state, "text", ConditionOperator.CONTAINS, text("Claw")));
        assertFalse(matches(state, "text", ConditionOperator.CONTAINS, text("claw")));
        assertFalse(matches(state, "text", ConditionOperator.CONTAINS, null));
        assertTrue(matches(state, "items", ConditionOperator.CONTAINS, text("alpha")));
        assertTrue(matches(state, "items", ConditionOperator.CONTAINS, JSON.valueToTree(2)));
        assertFalse(matches(state, "items", ConditionOperator.CONTAINS, text("missing")));
        assertFalse(matches(state, "object", ConditionOperator.CONTAINS, text("a")));
        assertFalse(matches(state, "missing", ConditionOperator.CONTAINS, text("a")));
    }

    @Test
    void numericOperatorsRequireTwoNumbersAndRespectBoundaries() {
        GraphState state = state("""
                {"low":2, "same":3.0, "high":4, "text":"4"}
                """);
        var three = JSON.valueToTree(3);

        assertFalse(matches(state, "low", ConditionOperator.GT, three));
        assertFalse(matches(state, "same", ConditionOperator.GT, three));
        assertTrue(matches(state, "high", ConditionOperator.GT, three));
        assertFalse(matches(state, "low", ConditionOperator.GTE, three));
        assertTrue(matches(state, "same", ConditionOperator.GTE, three));
        assertTrue(matches(state, "high", ConditionOperator.GTE, three));
        assertTrue(matches(state, "low", ConditionOperator.LT, three));
        assertFalse(matches(state, "same", ConditionOperator.LT, three));
        assertFalse(matches(state, "high", ConditionOperator.LT, three));
        assertTrue(matches(state, "low", ConditionOperator.LTE, three));
        assertTrue(matches(state, "same", ConditionOperator.LTE, three));
        assertFalse(matches(state, "high", ConditionOperator.LTE, three));

        assertFalse(matches(state, "text", ConditionOperator.GT, three));
        assertFalse(matches(state, "high", ConditionOperator.GT, text("3")));
        assertFalse(matches(state, "high", ConditionOperator.GT, null));
        assertFalse(matches(state, "missing", ConditionOperator.GT, three));
    }

    private static boolean matches(
            GraphState state, String path, ConditionOperator operator,
            com.fasterxml.jackson.databind.JsonNode expected) {
        return ConditionEvaluator.matches(state, new ConditionRule(path, operator, expected));
    }

    private static GraphState state(String source) {
        return GraphState.fromJson(source, JSON);
    }

    private static com.fasterxml.jackson.databind.JsonNode text(String value) {
        return JSON.valueToTree(value);
    }
}
