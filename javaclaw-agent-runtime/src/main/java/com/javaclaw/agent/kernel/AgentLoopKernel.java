package com.javaclaw.agent.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.javaclaw.agent.context.ContextContributor;
import com.javaclaw.agent.conversation.CompactionCoordinator;
import com.javaclaw.agent.model.ContextWindowExceededException;
import com.javaclaw.agent.model.ModelGateway;
import com.javaclaw.agent.model.ModelInvocationService;
import com.javaclaw.agent.prompt.ContextBlock;
import com.javaclaw.agent.prompt.PromptCompiler;
import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.agent.tool.StructuredResponses;
import com.javaclaw.agent.tool.TurnToolSession;
import com.javaclaw.agent.tool.TurnToolSessionFactory;
import com.javaclaw.core.api.ModelMessage;
import com.javaclaw.core.api.ModelResponse;
import com.javaclaw.core.api.ModelStreamSink;
import com.javaclaw.core.api.ProviderConversationState;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolExecutionResult;

/** One provider-neutral model/tool loop shared by every product entry point. */
public final class AgentLoopKernel implements AgentKernel {
    private static final int DEFAULT_MAX_TOOL_STEPS = 16;

    private final ModelInvocationService models;
    private final TurnToolSessionFactory tools;
    private final ContextAssembler contexts;
    private final java.util.function.BiFunction<
                    com.javaclaw.core.api.AgentThread,
                    com.javaclaw.core.api.TurnConfig,
                    com.javaclaw.agent.prompt.AgentsInstructionResolution>
            instructions;
    private final com.javaclaw.agent.automation.AutomationExecution automation;
    private final com.javaclaw.agent.knowledge.KnowledgeMaintenanceExecution maintenance;

    /** 装配共享 Agent Loop 与每 Turn 工具会话工厂；不添加额外上下文贡献者。 */
    public AgentLoopKernel(ModelGateway models, TurnToolSessionFactory tools) {
        this(models, tools, new ContextAssembler());
    }

    /** 固定模型网关、工具会话工厂和上下文贡献者；各 Profile 复用同一循环，不建立第二套 Runtime。 */
    public AgentLoopKernel(ModelGateway models, TurnToolSessionFactory tools, List<ContextContributor> contributors) {
        this(models, tools, new ContextAssembler(contributors));
    }

    /** 使用同一模型循环装配知识贡献和内容寻址附件解析；图片通过 Provider 多模态通道发送。 */
    public AgentLoopKernel(
            ModelGateway models,
            TurnToolSessionFactory tools,
            List<ContextContributor> contributors,
            com.javaclaw.agent.context.AttachmentInputResolver attachments) {
        this(models, tools, new ContextAssembler(contributors, attachments));
    }

    AgentLoopKernel(ModelGateway models, TurnToolSessionFactory tools, ContextAssembler contexts) {
        this(models, tools, contexts, null, null, null, null);
    }

    /** 装配领域策略所需的窄端口；自动化与普通对话继续使用同一模型循环及 Turn 工具快照。 */
    public AgentLoopKernel(
            ModelGateway models,
            TurnToolSessionFactory tools,
            List<ContextContributor> contributors,
            com.javaclaw.agent.context.AttachmentInputResolver attachments,
            com.javaclaw.agent.collaboration.CollaborationGateway collaboration,
            com.javaclaw.agent.tool.UserInputGateway inputs) {
        this(models, tools, new ContextAssembler(contributors, attachments), collaboration, inputs);
    }

    private AgentLoopKernel(
            ModelGateway models,
            TurnToolSessionFactory tools,
            ContextAssembler contexts,
            com.javaclaw.agent.collaboration.CollaborationGateway collaboration,
            com.javaclaw.agent.tool.UserInputGateway inputs) {
        this(models, tools, contexts, collaboration, inputs, null, null);
    }

    /** 装配有来源的低优先级维护步骤，仍使用同一 TurnScope、模型调用边界与持久 Item 生命周期。 */
    public AgentLoopKernel(
            ModelGateway models,
            TurnToolSessionFactory tools,
            List<ContextContributor> contributors,
            com.javaclaw.agent.context.AttachmentInputResolver attachments,
            com.javaclaw.agent.collaboration.CollaborationGateway collaboration,
            com.javaclaw.agent.tool.UserInputGateway inputs,
            com.javaclaw.agent.knowledge.KnowledgeMaintenanceExecution maintenance) {
        this(models, tools, new ContextAssembler(contributors, attachments), collaboration, inputs, maintenance, null);
    }

    private AgentLoopKernel(
            ModelGateway models,
            TurnToolSessionFactory tools,
            ContextAssembler contexts,
            com.javaclaw.agent.collaboration.CollaborationGateway collaboration,
            com.javaclaw.agent.tool.UserInputGateway inputs,
            com.javaclaw.agent.knowledge.KnowledgeMaintenanceExecution maintenance,
            com.javaclaw.agent.prompt.AgentsInstructionResolver instructionResolver) {
        this.models = new ModelInvocationService(Objects.requireNonNull(models, "models"));
        this.tools = Objects.requireNonNull(tools, "tools");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        instructions = instructionResolver == null
                ? (thread, config) ->
                        com.javaclaw.agent.prompt.AgentsInstructionResolution.empty(thread.workingDirectory())
                : instructionResolver::resolve;
        automation = new com.javaclaw.agent.automation.AutomationExecution(collaboration, inputs);
        this.maintenance = maintenance;
    }

    /** 装配文件系统 AGENTS.md 解析器；每个 Thread 使用自身 cwd 与沙箱可读根，不继承宿主任务缓存。 */
    public AgentLoopKernel(
            ModelGateway models,
            TurnToolSessionFactory tools,
            List<ContextContributor> contributors,
            com.javaclaw.agent.context.AttachmentInputResolver attachments,
            com.javaclaw.agent.collaboration.CollaborationGateway collaboration,
            com.javaclaw.agent.tool.UserInputGateway inputs,
            com.javaclaw.agent.knowledge.KnowledgeMaintenanceExecution maintenance,
            com.javaclaw.agent.prompt.AgentsInstructionResolver instructionResolver) {
        this(
                models,
                tools,
                new ContextAssembler(contributors, attachments),
                collaboration,
                inputs,
                maintenance,
                Objects.requireNonNull(instructionResolver, "instructionResolver"));
    }

    @Override
    public void execute(TurnExecutionContext context, ItemSink sink) throws Exception {
        context.throwIfInterrupted();
        if ("COMPACTION".equals(context.turn().config().attributes().get("invocationPurpose"))) {
            com.javaclaw.agent.conversation.CompactionExecution.execute(context, sink, models);
            return;
        }
        if ("KNOWLEDGE_MAINTENANCE".equals(context.turn().config().attributes().get("invocationPurpose"))) {
            if (maintenance == null) {
                throw new IllegalStateException("knowledge maintenance is unavailable");
            }
            maintenance.execute(context, sink, models);
            return;
        }
        if ("PROMPT_OPTIMIZATION".equals(context.turn().config().attributes().get("invocationPurpose"))) {
            optimizePrompt(context, sink);
            return;
        }
        var attributes = context.turn().config().attributes();
        if (java.util.Set.of("LOOP", "WORKFLOW", "SDD").contains(attributes.getOrDefault("profileKind", "CHAT"))
                && !attributes.containsKey("automationDefinition")) {
            throw new IllegalArgumentException("automation profiles require a versioned automation definition");
        }
        if (attributes.containsKey("adoptedPlanItemId")) {
            sink.append(new ThreadItem.Artifact(
                    context.turn().id().value() + ":plan-adoption",
                    "plan-adoption",
                    "用户显式采用计划",
                    1,
                    "已采用不可变 Plan Item；后续工具仍需按当前权限、审批和预算执行。计划摘要：" + attributes.get("adoptedPlanHash"),
                    List.of(attributes.get("adoptedPlanItemId"))));
        }
        // 预算属于显式 TurnScope；跨线程的 Hook、MCP sampling 与子调用不能重置账户。
        try (TurnToolSession turnTools = tools.open(context, sink)) {
            if (context.turn().config().attributes().containsKey("automationDefinition")) {
                automation.execute(
                        context,
                        sink,
                        turnTools,
                        (identity, task, purpose, allowed) ->
                                execute(context, sink, turnTools, identity, task, purpose, allowed));
            } else {
                execute(
                        context,
                        sink,
                        turnTools,
                        context.turn().id().value(),
                        "",
                        PromptCompiler.forProfile(context.turn().config()),
                        true);
            }
        }
    }

    private String execute(
            TurnExecutionContext context,
            ItemSink sink,
            TurnToolSession turnTools,
            String identity,
            String task,
            com.javaclaw.agent.prompt.PromptPurpose purpose,
            boolean toolsAllowed)
            throws Exception {
        boolean nativeConversation = models.compactionStrategy(context.turn().config())
                == com.javaclaw.agent.model.CompactionStrategy.NATIVE;
        ContextAssembler.Assembly assembled = contexts.assemble(context, nativeConversation);
        var visibleTools = toolsAllowed ? turnTools.availableTools() : List.<com.javaclaw.core.api.ToolDescriptor>of();
        var dialogue = new ArrayList<>(assembled.messages());
        if (!task.isBlank()) {
            dialogue.add(new ModelMessage(ModelMessage.Role.USER, task, null));
        }
        List<ContextBlock> references = assembled.contributions().stream()
                .map(value -> new ContextBlock(
                        switch (value.source()) {
                            case "memory" -> ContextBlock.Kind.MEMORY;
                            case "knowledge" -> ContextBlock.Kind.KNOWLEDGE;
                            case "skill-catalog" -> ContextBlock.Kind.SKILL_CATALOG;
                            default -> ContextBlock.Kind.REFERENCE;
                        },
                        value.source(),
                        value.sourceId(),
                        value.revision(),
                        value.content()))
                .toList();
        var prepared = models.prepare(
                purpose,
                context.turn().config(),
                visibleTools,
                references,
                instructions.apply(context.thread(), context.turn().config()),
                dialogue);
        boolean planProfile = "PLAN".equals(context.turn().config().attributes().getOrDefault("profileKind", "CHAT"));
        var planContract = StructuredResponses.plan();
        if (planProfile) {
            prepared = models.withContract(prepared, planContract);
        }
        var messages = new ArrayList<>(prepared.messages());
        assembled
                .contributions()
                .forEach(value -> sink.append(new ThreadItem.ContextUsage(
                        value.source(), value.sourceId(), value.revision(), summarize(value.content()))));
        int maxSteps = maxToolSteps(context);
        int maxIterations = context.turn().config().attributes().containsKey("automationDefinition")
                ? maxSteps + 1
                : Math.min(configuredLimit(context, "maxIterations", DEFAULT_MAX_TOOL_STEPS), maxSteps + 1);
        ProviderConversationState providerState = nativeConversation ? context.conversationState() : null;
        boolean recoveryCompactionPerformed = false;
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            context.throwIfInterrupted();
            appendSteering(context, messages);
            CompactionCoordinator.Result compacted = CompactionCoordinator.compactIfNeeded(
                    context, sink, models, prepared, messages, providerState, false);
            messages = new ArrayList<>(compacted.messages());
            providerState = compacted.state();
            java.util.concurrent.atomic.AtomicReference<ItemSink.ItemEmitter> streamed =
                    new java.util.concurrent.atomic.AtomicReference<>();
            ModelResponse response;
            while (true) {
                try {
                    response = models.stream(
                            context,
                            prepared,
                            messages,
                            visibleTools,
                            new ModelStreamSink() {
                                @Override
                                public void text(String fragment) {
                                    if (fragment == null || fragment.isEmpty() || planProfile) {
                                        return;
                                    }
                                    ItemSink.ItemEmitter emitter = streamed.updateAndGet(
                                            existing -> existing == null ? sink.start("agentMessage") : existing);
                                    emitter.delta(com.javaclaw.core.api.ItemDelta.text(fragment));
                                }

                                @Override
                                public void usage(com.javaclaw.core.api.ModelUsage value) {
                                    sink.usage(value);
                                }
                            },
                            sink,
                            providerState);
                    break;
                } catch (ContextWindowExceededException exceeded) {
                    if (recoveryCompactionPerformed) {
                        throw exceeded;
                    }
                    recoveryCompactionPerformed = true;
                    ItemSink.ItemEmitter partial = streamed.getAndSet(null);
                    if (partial != null) {
                        partial.fail("context_window_exceeded", "模型窗口超限；已丢弃未完成输出并尝试一次恢复压缩。", true);
                    }
                    CompactionCoordinator.Result recoveryCompacted = CompactionCoordinator.compactIfNeeded(
                            context, sink, models, prepared, messages, providerState, true);
                    messages = new ArrayList<>(recoveryCompacted.messages());
                    providerState = recoveryCompacted.state();
                }
            }
            if (response.conversationState() != null) {
                providerState = response.conversationState();
            }
            if (!response.reasoningSummary().isBlank()) {
                sink.append(new ThreadItem.ReasoningSummary(response.reasoningSummary()));
            }
            messages.add(
                    new ModelMessage(ModelMessage.Role.ASSISTANT, response.text(), null, null, response.toolCalls()));
            if (response.toolCalls().isEmpty()) {
                ItemSink.ItemEmitter emitter = streamed.get();
                ThreadItem finalItem = planProfile
                        ? models.validateOrRepair(
                                context,
                                prepared,
                                messages.subList(0, messages.size() - 1),
                                response,
                                planContract,
                                sink)
                        : new ThreadItem.AgentMessage(response.text());
                if (emitter == null) {
                    sink.append(finalItem);
                } else {
                    emitter.complete(finalItem);
                }
                persistConversationState(context, sink, response, providerState);
                return response.text();
            }
            ItemSink.ItemEmitter emitter = streamed.get();
            if (emitter != null) {
                emitter.complete(new ThreadItem.AgentMessage(response.text()));
            }
            persistConversationState(context, sink, response, providerState);
            if (iteration + 1 == maxIterations) {
                throw new IllegalStateException("Turn iteration budget exceeded: " + maxIterations);
            }
            if (!toolsAllowed) {
                throw new IllegalStateException("this artifact stage cannot execute tools");
            }
            for (var call : response.toolCalls()) {
                context.throwIfInterrupted();
                ToolExecutionResult result =
                        context.turn().config().attributes().containsKey("automationDefinition")
                                ? turnTools.execute(call, identity + ":" + call.name())
                                : turnTools.execute(call);
                sink.append(result.item());
                messages.add(new ModelMessage(
                        ModelMessage.Role.TOOL, result.modelContent(), call.id(), call.name(), java.util.List.of()));
            }
        }
        throw new IllegalStateException("agent step ended without a final result");
    }

    private static void persistConversationState(
            TurnExecutionContext context, ItemSink sink, ModelResponse response, ProviderConversationState state) {
        if (response.conversationState() == null || state == null) {
            return;
        }
        if (state.compacted()) {
            ItemSink.ItemEmitter compaction = sink.start("contextCompaction");
            compaction.completeCompaction(
                    new ThreadItem.ContextCompaction(),
                    com.javaclaw.agent.conversation.ConversationWindow.Replacement.nativeState(
                            context.turn().config().model(), context.thread().lastSequence(), state, response.usage()));
        } else {
            sink.providerConversationState(context.turn().config().model(), state.persistent(), response.usage());
        }
    }

    private void appendSteering(TurnExecutionContext context, ArrayList<ModelMessage> messages) {
        messages.addAll(contexts.assemble(List.of(), context.steering().drain()));
    }

    private static int maxToolSteps(TurnExecutionContext context) {
        String configured = context.turn().config().attributes().get("maxToolSteps");
        if (configured == null) {
            return DEFAULT_MAX_TOOL_STEPS;
        }
        try {
            return Math.max(0, Math.min(64, Integer.parseInt(configured)));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_TOOL_STEPS;
        }
    }

    private static int configuredLimit(TurnExecutionContext context, String name, int defaultValue) {
        String configured = context.turn().config().attributes().get(name);
        if (configured == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(configured);
            if (value == 0) {
                return defaultValue;
            }
            return Math.max(1, Math.min(1_000, value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private void optimizePrompt(TurnExecutionContext context, ItemSink sink) throws Exception {
        var attributes = context.turn().config().attributes();
        var contract = StructuredResponses.promptDraft(
                attributes.get("profileId"), Long.parseLong(attributes.get("profileRevision")));
        // 优化任务仅使用用户提交的草稿和明确能力资料，不继承历史、Memory、Skill 或项目私密规则。
        var dialogue = new ContextAssembler().assemble(List.of(), context.turn().input());
        var prepared = models.withContract(
                models.prepare(
                        com.javaclaw.agent.prompt.PromptPurpose.PROMPT_OPTIMIZATION,
                        context.turn().config(),
                        List.of(),
                        List.of(),
                        com.javaclaw.agent.prompt.AgentsInstructionResolution.empty(
                                context.thread().workingDirectory()),
                        dialogue),
                contract);
        ItemSink.ItemEmitter emitter = sink.start("promptDraft");
        try {
            ModelResponse response = models.complete(context, prepared, sink);
            emitter.complete(models.validateOrRepair(context, prepared, prepared.messages(), response, contract, sink));
        } catch (Exception failure) {
            emitter.fail("prompt_optimization_failed", "提示词草稿生成失败；原 Profile 未改变。", false);
            throw failure;
        }
    }

    private static String summarize(String value) {
        String normalized = value.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 497) + "...";
    }
}
