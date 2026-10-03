package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public interface FrameworkTool extends AutoCloseable {
    ToolDescriptor descriptor();

    JsonNode execute(JsonNode arguments, ToolExecutionContext context) throws Exception;

    /** Optional current host facts; informational only and never a permission grant. */
    default ToolRuntimeContextProvider runtimeContextProvider() { return null; }

    /** Built-in tools that return a status in-band override this method. */
    default ToolExecutionResultV1 executeResult(JsonNode arguments,
                                                ToolExecutionContext context) throws Exception {
        return ToolExecutionResultV1.success(execute(arguments, context));
    }

    /** Trusted resource identity for one-shot effect admission; empty uses the legacy key. */
    default String effectResourceKey(JsonNode arguments) { return ""; }

    /**
     * A compatibility hook for trusted, tool-specific effect inspection. Legacy tools, MCP
     * callbacks and plugins remain executable but cannot claim a verified effect by default.
     */
    default EffectReceiptV1 effectReceipt(JsonNode arguments, JsonNode rawOutput,
            ToolExecutionContext context, Instant observedAt) {
        return EffectReceiptV1.unknown(context.invocationId(), descriptor().name(),
                observedAt, "core.tool.completed:" + context.runId().value()
                        + ":" + context.invocationId());
    }

    @Override
    default void close() throws Exception {
        // Most tools are stateless. Run-scoped adapters override when they own resources.
    }
}
