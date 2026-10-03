package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.api.DefinitionValidationIssue;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.core.ToolArgumentValidationException;
import com.javaclaw.framework.core.ToolInvocationResult;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringAiToolCallbackEnvelopeTest {
    @Test
    void everyBusinessToolResponseHasAllFourTypedFields() {
        var result = new ToolInvocationResult(
                JsonNodeFactory.instance.objectNode().put("value", 1),
                Duration.ZERO, ToolExecutionStatus.SUCCEEDED);

        var visible = SpringAiToolCallback.modelVisibleResult(result);

        assertEquals("SUCCEEDED", visible.path("status").asText());
        assertEquals(1, visible.path("data").path("value").asInt());
        assertEquals("", visible.path("errorCode").asText());
        assertEquals("", visible.path("displayMessage").asText());
    }

    @Test
    void invalidArgumentsUseTheSameTypedEnvelope() {
        FrameworkTool tool = new FrameworkTool() {
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("example", "example",
                        JsonNodeFactory.instance.objectNode().put("type", "object"),
                        "test", PermissionSet.NONE, true);
            }
            @Override public com.fasterxml.jackson.databind.JsonNode execute(
                    com.fasterxml.jackson.databind.JsonNode arguments,
                    ToolExecutionContext context) {
                throw new AssertionError("invalid arguments must not execute");
            }
        };
        var invalid = new ToolArgumentValidationException(java.util.List.of(
                new DefinitionValidationIssue(DefinitionValidationIssue.Severity.ERROR,
                        "/value", "required", "missing")));

        var visible = SpringAiToolCallback.modelVisibleResult(
                SpringAiToolCallback.invalidArgumentsResult(tool, invalid));

        assertEquals("FAILED", visible.path("status").asText());
        assertEquals("INVALID_TOOL_ARGUMENTS", visible.path("errorCode").asText());
        assertEquals("invalid_tool_arguments", visible.path("data").path("error").asText());
        assertEquals(false, visible.path("data").path("executed").asBoolean());
    }
}
