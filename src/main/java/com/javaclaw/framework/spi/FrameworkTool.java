package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

public interface FrameworkTool extends AutoCloseable {
    ToolDescriptor descriptor();

    JsonNode execute(JsonNode arguments, ToolExecutionContext context) throws Exception;

    @Override
    default void close() throws Exception {
        // Most tools are stateless. Run-scoped adapters override when they own resources.
    }
}
