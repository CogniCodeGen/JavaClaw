package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JSON patch/output returned by an extension node without exposing WorkflowEngine internals. */
public record WorkflowNodeResult(
        ObjectNode setValues,
        String output,
        String interruptPrompt,
        String interruptResponseKey) {
    public WorkflowNodeResult {
        setValues = setValues == null ? JsonNodeFactory.instance.objectNode() : setValues.deepCopy();
        output = output == null ? "" : output;
        interruptPrompt = interruptPrompt == null ? "" : interruptPrompt;
        interruptResponseKey = interruptResponseKey == null ? "" : interruptResponseKey;
    }

    public static WorkflowNodeResult next(ObjectNode setValues) {
        return new WorkflowNodeResult(setValues, "", "", "");
    }
    @Override public ObjectNode setValues() { return setValues.deepCopy(); }
}
