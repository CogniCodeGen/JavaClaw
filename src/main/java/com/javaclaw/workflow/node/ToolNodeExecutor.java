package com.javaclaw.workflow.node;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolkitAssembler;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.runtime.NodeExecutionContext;
import com.javaclaw.workflow.runtime.NodeExecutor;
import com.javaclaw.workflow.runtime.NodeResult;
import com.javaclaw.workflow.runtime.GraphCancelledException;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 本地工具节点；工具对象本身继续执行 JavaClaw 的风险确认。 */
public final class ToolNodeExecutor implements NodeExecutor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> REMOTE_MCP_TOOLS = Set.of("mcp_list_tools", "mcp_call_tool");
    private final AgentRuntime validationRuntime;

    public ToolNodeExecutor() { this(null); }
    public ToolNodeExecutor(AgentRuntime validationRuntime) { this.validationRuntime = validationRuntime; }

    @Override public String type() { return "tool"; }

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
        if (validationRuntime != null && !name.isBlank()) {
            Toolkit toolkit = buildLocalToolkit(validationRuntime);
            if (toolkit.getTool(name) == null) errors.add("本地工具不存在: " + name);
        }
        return List.copyOf(errors);
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) {
        AgentRuntime runtime = context.require(AgentRuntime.class);
        JsonNode config = context.node().config();
        Toolkit toolkit = buildLocalToolkit(runtime);
        List<String> groups = WorkflowToolGroupPolicy.read(config.path("toolGroups"));
        WorkflowToolGroupPolicy.restrict(toolkit, groups, true);

        String toolName = config.path("toolName").asText();
        if ("mcp".equals(config.path("source").asText()) || REMOTE_MCP_TOOLS.contains(toolName)) {
            throw new SecurityException("自定义工作流默认不允许远程 MCP 工具");
        }
        if (toolkit.getTool(toolName) == null) throw new IllegalArgumentException("本地工具不存在: " + toolName);
        JsonNode rendered = TemplateRenderer.renderJson(config.path("arguments"), context.state());
        Map<String, Object> input = rendered == null || !rendered.isObject() ? Map.of()
                : MAPPER.convertValue(rendered, new TypeReference<>() {});
        ToolUseBlock block = new ToolUseBlock(UUID.randomUUID().toString(), toolName, input);
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(block).input(input).build();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ToolResultBlock> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var subscription = toolkit.callTool(param).subscribe(value::set, error -> {
            failure.set(error); done.countDown();
        }, done::countDown);
        try (AutoCloseable ignored = context.cancellation().onCancel(subscription::dispose)) {
            while (!done.await(100, TimeUnit.MILLISECONDS)) context.cancellation().throwIfCancelled();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            context.cancellation().throwIfCancelled();
            throw new IllegalStateException("工具执行被中断", e);
        } catch (GraphCancelledException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("工具取消钩子关闭失败", e);
        }
        context.cancellation().throwIfCancelled();
        if (failure.get() != null) throw new IllegalStateException("工具执行失败: " + toolName, failure.get());
        ToolResultBlock result = value.get();
        if (result == null) throw new IllegalStateException("工具未返回结果: " + toolName);
        StringBuilder text = new StringBuilder();
        for (var output : result.getOutput()) {
            if (output instanceof TextBlock tb) text.append(tb.getText());
            else text.append(output);
        }
        String resultText = text.toString();
        ConversationCallbacks callbacks = context.optional(ConversationCallbacks.class);
        if (callbacks != null) callbacks.onEvent(new ConversationEvent.ToolResult(toolName, resultText));
        if (isFailureResult(resultText)) {
            throw new IllegalStateException("工具返回失败: " + resultText);
        }
        String outputKey = config.path("outputKey").asText("tool.output");
        return NodeResult.next(StatePatch.builder().set(outputKey, resultText).build());
    }

    static boolean isFailureResult(String text) {
        if (text == null) return true;
        String normalized = text.stripLeading();
        if (normalized.startsWith("Error:")) return true;
        if (!normalized.startsWith("[")) return false;
        int statusStart = normalized.indexOf("][");
        if (statusStart < 2) return false;
        int statusEnd = normalized.indexOf(']', statusStart + 2);
        if (statusEnd < 0) return false;
        String status = normalized.substring(statusStart + 2, statusEnd);
        return "失败".equals(status) || "超时".equals(status);
    }

    private static Toolkit buildLocalToolkit(AgentRuntime runtime) {
        Toolkit toolkit = ToolkitAssembler.buildBaseToolkit(runtime, runtime.getExpertManager(),
                true, ToolCallOrigin.INTERACTIVE);
        // 主编排 Toolkit 只包含专家代理；TOOL 节点还需要直接注册专家背后的本地 @Tool。
        runtime.getExpertManager().getCapabilityTools().forEach((group, tools) ->
                toolkit.registration().tool(tools).group(group).apply());
        return toolkit;
    }
}
