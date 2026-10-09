package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.framework.core.ReasoningRequest;
import com.javaclaw.framework.core.ToolArgumentValidationException;
import com.javaclaw.framework.core.ToolInvocationGateway;
import com.javaclaw.framework.core.ToolInvocationRequest;
import com.javaclaw.framework.core.ToolInvocationResult;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.RunStore;
import com.javaclaw.framework.spi.ToolExecutionContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Objects;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** Spring AI callback adapter. Actual execution always delegates to ToolInvocationGateway. */
final class SpringAiToolCallback implements ToolCallback, SpringAiToolCatalog.GroupedCallback {
    private final FrameworkTool tool;
    private final ReasoningRequest reasoning;
    private final ToolInvocationGateway gateway;
    private final ObjectMapper json;
    private final ToolDefinition definition;
    private final ModelStepJournal journal;

    SpringAiToolCallback(
            FrameworkTool tool,
            ReasoningRequest reasoning,
            ToolInvocationGateway gateway,
            ObjectMapper json) {
        this(tool, reasoning, gateway, json, null);
    }

    SpringAiToolCallback(FrameworkTool tool, ReasoningRequest reasoning,
                         ToolInvocationGateway gateway, ObjectMapper json, ModelStepJournal journal) {
        this.tool = Objects.requireNonNull(tool, "tool");
        this.reasoning = Objects.requireNonNull(reasoning, "reasoning");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.json = Objects.requireNonNull(json, "json");
        this.journal = journal;
        var descriptor = tool.descriptor();
        this.definition = ToolDefinition.builder()
                .name(descriptor.name())
                .description(descriptor.description())
                .inputSchema(descriptor.inputSchema().toString())
                .build();
    }

    @Override
    public String group() {
        return tool.descriptor().group();
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
            JsonNode arguments;
            try {
                arguments = parseArguments(json, toolInput);
            } catch (ToolArgumentValidationException invalid) {
                return journal == null ? modelVisibleResult(invalidArgumentsResult(tool, invalid)).toString()
                        : journal.rejectMalformedArgumentsForModel(tool, toolInput, invalid).toString();
            }
            if (journal != null) {
                return json.writeValueAsString(journal.invokeForModel(tool, arguments, gateway));
            }
            ToolInvocationResult result = invoke(
                    tool, arguments, reasoning, gateway, UUID.randomUUID().toString());
            return json.writeValueAsString(modelVisibleResult(result));
        } catch (ToolArgumentValidationException invalid) {
            // Production calls use ModelStepJournal, which persists this feedback first.
            // Keep standalone callbacks recoverable without masking any other failure.
            if (journal != null) throw invalid;
            return modelVisibleResult(invalidArgumentsResult(tool, invalid)).toString();
        } catch (CompletionException failure) {
            throw propagate(failure);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("tool callback failed", failure);
        }
    }

    /** Reject invalid JSON before the invocation boundary; never reconstruct missing arguments. */
    static JsonNode parseArguments(ObjectMapper json, String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode parsed = json.reader().with(
                        com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
                if (parsed != null) return parsed;
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                // Parser exception messages can contain argument values; feedback is fixed host text.
            }
        }
        throw new ToolArgumentValidationException(java.util.List.of(
                new com.javaclaw.framework.api.DefinitionValidationIssue(
                        com.javaclaw.framework.api.DefinitionValidationIssue.Severity.ERROR,
                        "/arguments", "json", "Arguments must be one complete JSON object with no trailing content.")));
    }

    static ObjectNode modelVisibleResult(ToolInvocationResult result) {
        ObjectNode modelResult = JsonNodeFactory.instance.objectNode();
        modelResult.put("status", result.status().name());
        modelResult.set("data", result.output());
        modelResult.put("errorCode", result.errorCode());
        JsonNode data = result.output();
        boolean discovery = DesktopDiscoveryModelProjection.recognizes("desktop_session_targets", data)
                || DesktopDiscoveryModelProjection.recognizes("desktop_session_window_candidates", data);
        // The original display remains in the durable tool event. Reattaching it would undo result eviction.
        modelResult.put("displayMessage", discovery ? DesktopDiscoveryModelProjection.DISPLAY
                : data.path("truncated").asBoolean(false) && data.has("preview")
                ? "工具结果已裁剪；完整内容保存在执行日志。" : result.displayMessage());
        modelResult.putArray("evidenceRefs");
        return modelResult;
    }

    static ObjectNode modelVisibleResult(ToolInvocationResult result,
            RunStore runs, RunId runId, String invocationId) {
        ObjectNode modelResult = modelVisibleResult(result);
        if (result.status() == ToolExecutionStatus.SUCCEEDED) {
            var refs = modelResult.withArray("evidenceRefs");
            HarnessDecisionEvidence.currentInvocationRefs(runs, runId, invocationId)
                    .forEach(refs::add);
        }
        return modelResult;
    }

    static ObjectNode invalidArgumentsFeedback(
            FrameworkTool tool, ToolArgumentValidationException invalid) {
        var descriptor = tool.descriptor();
        ObjectNode feedback = JsonNodeFactory.instance.objectNode()
                .put("error", "invalid_tool_arguments")
                .put("tool", descriptor.name())
                .put("executed", false)
                .put("message", "The tool call was rejected before execution. Retry using the input schema; "
                        + "do not claim that the requested operation was completed.");
        var issues = feedback.putArray("issues");
        for (var issue : invalid.issues()) {
            issues.addObject().put("path", issue.path())
                    .put("code", issue.code())
                    .put("message", issue.message());
        }
        var schema = descriptor.inputSchema();
        var required = feedback.putArray("requiredProperties");
        schema.path("required").forEach(value -> {
            if (value.isTextual()) required.add(value.asText());
        });
        var allowed = feedback.putArray("allowedProperties");
        schema.path("properties").fieldNames().forEachRemaining(allowed::add);
        return feedback;
    }

    static ToolInvocationResult invalidArgumentsResult(
            FrameworkTool tool, ToolArgumentValidationException invalid) {
        return new ToolInvocationResult(invalidArgumentsFeedback(tool, invalid),
                Duration.ZERO, ToolExecutionStatus.FAILED,
                "INVALID_TOOL_ARGUMENTS", "Tool arguments were rejected before execution");
    }

    static ToolInvocationResult invoke(
            FrameworkTool tool,
            JsonNode arguments,
            ReasoningRequest reasoning,
            ToolInvocationGateway gateway,
            String invocationId) {
        return invoke(tool, arguments, reasoning, gateway, invocationId, false);
    }

    static ToolInvocationResult invokeInline(
            FrameworkTool tool, JsonNode arguments, ReasoningRequest reasoning,
            ToolInvocationGateway gateway, String invocationId) {
        return invoke(tool, arguments, reasoning, gateway, invocationId, true);
    }

    private static ToolInvocationResult invoke(
            FrameworkTool tool, JsonNode arguments, ReasoningRequest reasoning,
            ToolInvocationGateway gateway, String invocationId, boolean inline) {
        try {
            ToolExecutionContext context = new ToolExecutionContext(
                    reasoning.runId(), invocationId, reasoning.control(),
                    reasoning.control().deadline());
            ToolInvocationRequest invocation = new ToolInvocationRequest(
                    tool, arguments, context, reasoning.runRequest(),
                    reasoning.plan().descriptor().permissions(),
                    reasoning.plan().descriptor().toolPolicy(),
                    reasoning.plan().toolPolicies(),
                    reasoning.plan().toolResultPostProcessors(), reasoning.control(),
                    reasoning.events());
            return inline ? gateway.invokeInline(invocation)
                    : gateway.invoke(invocation).toCompletableFuture().join();
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
