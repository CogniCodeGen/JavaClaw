package com.javaclaw.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.framework.api.InvocationSource;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.RunBudget;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.api.ToolCallOutcome;
import com.javaclaw.framework.api.ToolCallRequest;
import com.javaclaw.framework.api.ToolClient;
import com.javaclaw.framework.api.ToolExecutionStatus;
import com.javaclaw.runtime.WorkspaceContext;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.runtime.NodeExecutionContext;
import com.javaclaw.workflow.runtime.NodeExecutor;
import com.javaclaw.workflow.runtime.NodeResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/** Workflow TOOL node. It resolves and invokes tools only through the framework ToolClient. */
public final class ToolNodeExecutor implements NodeExecutor {
    private static final Set<String> REMOTE_MCP_TOOLS = Set.of("mcp_list_tools", "mcp_call_tool");
    private final ToolClient tools;
    private final WorkspaceContext workspace;

    /** Validation-only constructor used by graph schema tests. */
    public ToolNodeExecutor() {
        this(null, null);
    }

    public ToolNodeExecutor(ToolClient tools, WorkspaceContext workspace) {
        this.tools = tools;
        this.workspace = workspace;
    }

    @Override
    public String type() {
        return "tool";
    }

    @Override
    public List<String> validate(com.javaclaw.workflow.model.NodeDefinition node) {
        List<String> errors = new ArrayList<>();
        String name = node.config().path("toolName").asText();
        if (name.isBlank()) errors.add("TOOL 必须配置 toolName");
        if ("mcp".equals(node.config().path("source").asText()) || REMOTE_MCP_TOOLS.contains(name)) {
            errors.add("自定义工作流默认不允许远程 MCP 工具");
        }
        if (node.config().has("arguments") && !node.config().path("arguments").isObject()) {
            errors.add("TOOL arguments 必须是 JSON 对象");
        }
        if (node.config().has("toolGroups") && !node.config().path("toolGroups").isArray()) {
            errors.add("TOOL toolGroups 必须是数组");
        }
        WorkflowToolGroupPolicy.validate(node.config().path("toolGroups"), errors);
        StatePathValidator.validate(node.config().path("outputKey").asText("tool.output"),
                "TOOL outputKey", errors);
        return List.copyOf(errors);
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) throws Exception {
        if (tools == null || workspace == null) {
            throw new IllegalStateException("TOOL executor is not bound to ToolClient");
        }
        JsonNode config = context.node().config();
        String toolName = config.path("toolName").asText();
        if ("mcp".equals(config.path("source").asText()) || REMOTE_MCP_TOOLS.contains(toolName)) {
            throw new SecurityException("自定义工作流默认不允许远程 MCP 工具");
        }
        JsonNode arguments = TemplateRenderer.renderJson(config.path("arguments"), context.state());
        if (arguments == null || !arguments.isObject()) {
            arguments = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        }
        long timeoutSeconds = Math.max(1, config.path("timeoutSeconds").asLong(120));
        PermissionSet permissions = config.path("allowMutatingTools").asBoolean(false)
                ? PermissionSet.of("tool.read", "tool.execute")
                : PermissionSet.of("tool.read");
        ToolCallRequest request = new ToolCallRequest(
                new RunScope(workspace.workspaceId(), "local-user", context.threadId()),
                InvocationSource.workflow(context.runId()), toolName, arguments, permissions,
                new RunBudget(Duration.ofSeconds(timeoutSeconds), 0, 0, 1,
                        new BigDecimal("10")), context.runId(),
                context.cancellation(),
                Set.copyOf(WorkflowToolGroupPolicy.read(config.path("toolGroups"))),
                context.ownerRunId(), context.invocationId(), context.orchestrationStepId());
        ToolCallOutcome outcome;
        try {
            // The ToolClient stage settles only after its managed task has really terminated
            // and all run-scoped tools/extension leases have been closed.
            outcome = tools.invoke(request).toCompletableFuture().join();
        } catch (CompletionException failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof com.javaclaw.framework.api.TurnPausedException paused) throw paused;
            if (cause instanceof TimeoutException timeout) {
                throw new IllegalStateException("工具节点执行超时: " + toolName, timeout);
            }
            throw new IllegalStateException("工具执行失败: " + toolName, cause);
        } catch (CancellationException cancelled) {
            context.cancellation().throwIfCancelled();
            throw new IllegalStateException("工具执行被取消: " + toolName, cancelled);
        }
        context.cancellation().throwIfCancelled();
        JsonNode rawOutput = outcome.output();
        String resultText = rawOutput == null ? ""
                : rawOutput.isTextual() ? rawOutput.asText() : rawOutput.toString();
        ConversationCallbacks callbacks = context.callbacks();
        if (callbacks != null) callbacks.onEvent(new ConversationEvent.ToolResult(
                toolName, resultText, context.invocationId(), rawOutput, outcome.status()));
        if (outcome.status() != ToolExecutionStatus.SUCCEEDED) {
            throw new com.javaclaw.framework.api.TurnPausedException(
                    "工具节点状态为 " + outcome.status()
                            + (outcome.errorCode().isBlank() ? "" : " (" + outcome.errorCode() + ")")
                            + "；需要核对结果后才能继续或重试");
        }
        String outputKey = config.path("outputKey").asText("tool.output");
        var value = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        value.put("status", outcome.status().name());
        value.set("value", rawOutput == null
                ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : rawOutput);
        return NodeResult.next(StatePatch.builder().setJson(outputKey, value).build());
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }
}
