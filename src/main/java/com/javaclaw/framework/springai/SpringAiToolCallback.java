package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.framework.core.*;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.ToolExecutionContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** Spring AI callback adapter. Actual execution always delegates to ToolInvocationGateway. */
final class SpringAiToolCallback implements ToolCallback {
    private final FrameworkTool tool;
    private final ReasoningRequest reasoning;
    private final ToolInvocationGateway gateway;
    private final ObjectMapper json;
    private final ToolDefinition definition;

    SpringAiToolCallback(
            FrameworkTool tool,
            ReasoningRequest reasoning,
            ToolInvocationGateway gateway,
            ObjectMapper json) {
        this.tool = Objects.requireNonNull(tool, "tool");
        this.reasoning = Objects.requireNonNull(reasoning, "reasoning");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.json = Objects.requireNonNull(json, "json");
        var descriptor = tool.descriptor();
        this.definition = ToolDefinition.builder()
                .name(descriptor.name())
                .description(descriptor.description())
                .inputSchema(descriptor.inputSchema().toString())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().returnDirect(false).build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext ignored) {
        try {
            JsonNode arguments = json.readTree(toolInput);
            ToolInvocationResult result = invoke(
                    tool, arguments, reasoning, gateway, UUID.randomUUID().toString());
            return json.writeValueAsString(result.output());
        } catch (CompletionException failure) {
            throw propagate(failure);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("tool callback failed", failure);
        }
    }

    static ToolInvocationResult invoke(
            FrameworkTool tool,
            JsonNode arguments,
            ReasoningRequest reasoning,
            ToolInvocationGateway gateway,
            String invocationId) {
        try {
            ToolExecutionContext context = new ToolExecutionContext(
                    reasoning.runId(), invocationId, reasoning.control(),
                    reasoning.control().deadline());
            return gateway.invoke(new ToolInvocationRequest(
                    tool, arguments, context, reasoning.runRequest(),
                    reasoning.plan().descriptor().permissions(),
                    reasoning.plan().descriptor().toolPolicy(),
                    reasoning.plan().toolPolicies(),
                    reasoning.plan().toolResultPostProcessors(), reasoning.control(),
                    reasoning.events())).toCompletableFuture().join();
        } catch (CompletionException failure) {
            throw propagate(failure);
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current instanceof RuntimeException runtime
                ? runtime : new IllegalStateException(current);
    }
}
