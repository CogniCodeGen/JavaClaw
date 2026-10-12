package com.javaclaw.framework.builtin.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.spi.*;

/** The model may retrieve more history without selecting or traversing another thread. */
final class MemoryRecallTool implements FrameworkTool {
    private final MemoryRecallGateway recall;
    private final ToolContext owner;
    MemoryRecallTool(MemoryRecallGateway recall, ToolContext owner) { this.recall = recall; this.owner = owner; }
    @Override public ToolDescriptor descriptor() {
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("query");
        var properties = schema.putObject("properties");
        properties.putObject("query").put("type", "string").put("minLength", 1);
        properties.putObject("topK").put("type", "integer").put("minimum", 1).put("maximum", 50);
        return new ToolDescriptor("memory_recall", "Find relevant prior conclusions, decisions and evidence in this thread and personal habits.",
                schema, "memory", PermissionSet.of("tool.read"), true);
    }
    @Override public JsonNode execute(JsonNode arguments, ToolExecutionContext context) {
        if (!owner.runId().equals(context.runId())) throw new SecurityException("memory owner mismatch");
        context.cancellation().throwIfCancelled();
        return JsonNodeFactory.instance.objectNode().put("context", recall.recall(owner.request(),
                arguments.path("query").asText(), arguments.path("topK").asInt(8)));
    }
}
