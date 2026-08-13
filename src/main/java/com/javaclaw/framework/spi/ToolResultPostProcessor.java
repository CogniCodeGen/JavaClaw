package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunRequest;

/** Produces the bounded model-facing view; the unmodified result remains in the durable event. */
@FunctionalInterface
public interface ToolResultPostProcessor {
    JsonNode process(
            JsonNode currentModelView,
            ToolDescriptor tool,
            ToolExecutionContext context,
            RunRequest request);
}
