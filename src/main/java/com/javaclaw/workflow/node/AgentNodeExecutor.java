package com.javaclaw.workflow.node;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolkitAssembler;
import com.javaclaw.agent.handler.StreamEventHandler;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.runtime.GraphCancelledException;
import com.javaclaw.workflow.runtime.NodeExecutionContext;
import com.javaclaw.workflow.runtime.NodeExecutor;
import com.javaclaw.workflow.runtime.NodeResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Toolkit;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 通用 Agent 节点：每次执行创建隔离的 ReActAgent，流式事件复用现有转换器。 */
public final class AgentNodeExecutor implements NodeExecutor {
    private final AgentRuntime validationRuntime;

    public AgentNodeExecutor() { this(null); }
    public AgentNodeExecutor(AgentRuntime validationRuntime) { this.validationRuntime = validationRuntime; }

    @Override public String type() { return "agent"; }

    @Override
    public List<String> validate(com.javaclaw.workflow.model.NodeDefinition node) {
        List<String> errors = new ArrayList<>();
        if (node.config().path("prompt").asText().isBlank()
                && node.config().path("expertRef").asText().isBlank()) {
            errors.add("AGENT 必须配置 prompt 或 expertRef");
        }
        String expertRef = node.config().path("expertRef").asText();
        if (validationRuntime != null && !expertRef.isBlank()
                && validationRuntime.getExpertManager().getExpertDefs().stream()
                .noneMatch(d -> d.toolName().equals(expertRef) || d.agentName().equals(expertRef))) {
            errors.add("未知专家: " + expertRef);
        }
        int maxIters = node.config().path("maxIters").asInt(8);
        if (maxIters < 1 || maxIters > 100) errors.add("maxIters 必须在 1..100 之间");
        if (node.config().has("toolGroups") && !node.config().path("toolGroups").isArray()) {
            errors.add("AGENT toolGroups 必须是数组");
        }
        WorkflowToolGroupPolicy.validate(node.config().path("toolGroups"), errors);
        StatePathValidator.validate(node.config().path("outputKey").asText("agent.output"),
                "AGENT outputKey", errors);
        return errors;
    }

    @Override
    public NodeResult execute(NodeExecutionContext context) throws Exception {
        AgentRuntime runtime = context.require(AgentRuntime.class);
        ConversationCallbacks outer = context.optional(ConversationCallbacks.class);
        ConversationCallbacks callbacks = outer == null ? silentCallbacks() : outer;
        var config = context.node().config();

        String sysPrompt = config.path("prompt").asText();
        int maxIters = config.path("maxIters").asInt(AgentConfig.getInstance().getOrchestratorMaxIters());
        String expertRef = config.path("expertRef").asText();
        if (!expertRef.isBlank()) {
            var def = runtime.getExpertManager().getExpertDefs().stream()
                    .filter(d -> d.toolName().equals(expertRef) || d.agentName().equals(expertRef))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("未知专家: " + expertRef));
            if (sysPrompt.isBlank()) sysPrompt = def.sysPrompt();
            if (!config.has("maxIters")) maxIters = def.maxIters();
        }

        Toolkit toolkit = ToolkitAssembler.buildBaseToolkit(runtime, runtime.getExpertManager(),
                true, ToolCallOrigin.INTERACTIVE);
        List<String> groups = WorkflowToolGroupPolicy.read(config.path("toolGroups"));
        // AgentScope 的 setActiveGroups 只会激活指定组，不会关闭创建时已激活的组。
        // 先显式停用全部已知组，并物理移除 MCP 桥，再只开启工作流声明的本地能力。
        WorkflowToolGroupPolicy.restrict(toolkit, groups, false);

        String profile = config.path("modelProfile").asText("default");
        var model = switch (profile) {
            case "light" -> runtime.getModelFactory().createLightChatModel();
            case "high" -> runtime.getModelFactory().createHighChatModel();
            case "multi" -> runtime.getModelFactory().createMultiAgentChatModel();
            default -> runtime.getModelFactory().createChatModel();
        };
        ReActAgent agent = ReActAgent.builder()
                .name(config.path("name").asText(context.node().label()))
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(toolkit)
                .memory(runtime.getModelFactory().defaultAutoContextMemory())
                .modelExecutionConfig(runtime.getModelExecConfig())
                .maxIters(maxIters)
                .build();

        String input = TemplateRenderer.render(config.path("inputTemplate").asText("{{input}}"), context.state());
        Msg msg = Msg.builder().role(MsgRole.USER).name("user").textContent(input).build();
        StringBuilder reply = new StringBuilder();
        StreamEventHandler eventHandler = new StreamEventHandler();
        ConversationCallbacks capturing = new ConversationCallbacks() {
            @Override public void onEvent(ConversationEvent event) {
                if (event instanceof ConversationEvent.Reply r) {
                    // 最终用户可见输出由 OUTPUT 节点统一发送，避免 AGENT→OUTPUT 重复回复。
                    reply.append(r.chunk());
                } else {
                    callbacks.onEvent(event);
                }
            }
            @Override public void onTerminal(ConversationOutcome outcome) { }
        };

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Disposable subscription = agent.stream(msg, ToolkitAssembler.buildStreamOptions(AgentConfig.getInstance()))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> done.countDown())
                .subscribe(event -> eventHandler.handleEvent(event, capturing), failure::set, () -> {});
        try (AutoCloseable ignored = context.cancellation().onCancel(subscription::dispose)) {
            long timeout = Math.max(30L, AgentConfig.getInstance().getReadTimeoutSeconds() * 2L);
            while (!done.await(Math.min(timeout, 1L), TimeUnit.SECONDS)) {
                context.cancellation().throwIfCancelled();
                timeout--;
                if (timeout <= 0) {
                    subscription.dispose();
                    throw new IllegalStateException("Agent 节点执行超时");
                }
            }
        }
        context.cancellation().throwIfCancelled();
        if (failure.get() != null) throw new IllegalStateException("Agent 节点失败", failure.get());
        String outputKey = config.path("outputKey").asText("agent.output");
        return NodeResult.output(StatePatch.builder().set(outputKey, reply.toString()).build(), reply.toString());
    }

    private static ConversationCallbacks silentCallbacks() {
        return new ConversationCallbacks() {
            @Override public void onEvent(ConversationEvent event) { }
            @Override public void onTerminal(ConversationOutcome outcome) { }
        };
    }
}
