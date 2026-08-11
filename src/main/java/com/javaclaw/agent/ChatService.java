package com.javaclaw.agent;

import com.javaclaw.agent.evaluation.EvaluationPipeline;
import com.javaclaw.agent.execution.ExecutionMonitor;
import com.javaclaw.agent.goal.GoalDecomposition;
import com.javaclaw.agent.goal.GoalManager;
import com.javaclaw.agent.planning.PlanEvolver;
import com.javaclaw.agent.handler.StreamEventHandler;
import com.javaclaw.agent.hook.AgentLoggingHook;
import com.javaclaw.agent.hook.LoopDetectionHook;
import com.javaclaw.agent.hook.ToolFallbackHook;
import com.javaclaw.agent.router.RoutingResult;
import com.javaclaw.agent.router.ToolRouter;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.platform.execution.ManagedTask;
import com.javaclaw.platform.execution.TaskHandle;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.chat.ChatMessage;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.memory.correction.CorrectionGuard;
import com.javaclaw.memory.correction.CorrectionTurnContext;
import com.javaclaw.prompt.AgentPrompts;
import com.javaclaw.skill.SkillRuntimeServices;
import com.javaclaw.util.AtomicDisposable;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 普通聊天模式门面（UI 无关）
 *
 * <p>对外只暴露事件流接口 {@link #streamChat(ConversationRequest, ConversationCallbacks)}。
 * 业务侧的所有副作用（token 统计、GEPA 评估、执行监控、计划自适应）都在本服务内部完成，
 * 不泄露给调用方；UI 层只需消费 {@link ConversationEvent}。</p>
 *
 * <p>核心职责：
 * <ul>
 *   <li>维护主编排智能体 {@code orchestrator}（ReActAgent，带 PlanNotebook）</li>
 *   <li>承载 GEPA 过程评估能力（目标分解、过程评估、自适应规划、执行监控）</li>
 *   <li>工具路由（按用户意图按需激活工具组，降低 token 消耗）</li>
 *   <li>流式会话管理（启动、取消、token 统计）</li>
 *   <li>会话状态持久化（保存 / 恢复 / 删除）</li>
 * </ul>
 *
 * <p>所有基础设施通过 {@link AgentRuntime} 注入。规划模式和托管任务模式有各自独立的
 * 服务入口，与本类平行。</p>
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final com.javaclaw.workflow.model.GraphDefinition SYSTEM_GRAPH =
            com.javaclaw.workflow.service.SystemGraphFactory.chat();

    /** 共享基础设施容器 */
    private final AgentRuntime runtime;
    private final com.javaclaw.workflow.service.WorkflowService workflowService;
    private final TaskScope taskScope;
    private final SkillRuntimeServices skills;
    private final com.javaclaw.api.conversation.SingleConversationRun conversationRun =
            new com.javaclaw.api.conversation.SingleConversationRun();

    /** 主编排工具集（带工具分组，每轮按路由结果激活子集） */
    private final Toolkit masterToolkit;

    /** 基础系统提示词（不含动态技能和 MCP 提示词，每轮按路由拼接） */
    private final String baseSystemPrompt;

    /** 流式输出选项（根据思考模式决定是否包含推理事件） */
    private final StreamOptions streamOptions;

    /** 循环检测钩子（拦截连续相同工具调用） */
    private final LoopDetectionHook loopDetectionHook;

    /** 工具降级钩子（工具失败时尝试回退路径） */
    private final ToolFallbackHook toolFallbackHook;

    /** 全生命周期日志钩子 */
    private final AgentLoggingHook loggingHook;

    /** 流式事件处理器（按事件类型分发到 ConversationCallbacks） */
    private final StreamEventHandler eventHandler;

    /** 工具路由器（可空；禁用时每轮加载全部工具） */
    private final ToolRouter toolRouter;

    /** GEPA — 目标管理器（分解用户请求为可验证目标，可空） */
    private final GoalManager goalManager;

    /** GEPA — 过程评估流水线（每 N 次工具调用触发中间评估） */
    private final EvaluationPipeline evaluationPipeline;

    /** GEPA — 计划演进器（计划版本管理 + 统一演进入口，可空） */
    private final PlanEvolverAccessor planningEngineAccessor;

    /** GEPA — 执行监控器（工具调用轨迹 + 连续失败检测） */
    private final ExecutionMonitor executionMonitor;

    /** 主编排智能体（每轮重建，带过滤后的工具组） */
    private volatile ReActAgent orchestrator;

    /** 保护 orchestrator 重建与访问的原子性 */
    private final Object orchestratorLock = new Object();

    /** 当前活跃的流式订阅（内部 CAS 保证 set/dispose 原子性） */
    private final AtomicDisposable activeSubscription = new AtomicDisposable();

    /** 澄清中断工具（模型主动调用以打断本轮并向用户提问） */
    private final com.javaclaw.agent.clarify.ClarifyTools clarifyTools =
            new com.javaclaw.agent.clarify.ClarifyTools();

    /** 记忆服务（EclipseStore 统一记忆基座：人格 + 语义事实 + 情景 + 检查点 + 变更日志） */
    private final com.javaclaw.memory.MemoryService memoryService;

    private final ChatTurnPreparationPipeline turnPreparation;
    private final ChatTurnCompletionPipeline turnCompletion;
    private final ChatSessionStateStore sessionStates;

    /** 本轮显式路由注入的技能名（全量注入时为空列表，不计入使用统计） */
    private volatile List<String> turnInjectedSkills = List.of();

    /** 本轮用户输入（供按 query 检索相关记忆注入；每轮重建编排器时读取） */
    private volatile String currentUserInput = "";

    /**
     * 构造并初始化普通模式服务。
     *
     * @param runtime 共享基础设施
     */
    public ChatService(AgentRuntime runtime,
                       com.javaclaw.workflow.service.WorkflowService workflowService,
                       com.javaclaw.skill.curation.SkillCurator skillCurator,
                       TaskScope taskScope) {
        this.runtime = runtime;
        this.workflowService = workflowService;
        this.taskScope = java.util.Objects.requireNonNull(taskScope, "taskScope");
        this.skills = runtime.getSkillRuntime();
        com.javaclaw.skill.curation.SkillCurator configuredCurator =
                java.util.Objects.requireNonNull(skillCurator, "skillCurator");
        if (workflowService != null) workflowService.systemGraphs().register(SYSTEM_GRAPH);
        AgentConfig config = runtime.getConfig();
        log.info("========== 初始化 ChatService 普通模式 ==========");

        // 0. 记忆服务：打开当前工作区的 EclipseStore 记忆库（人格默认骨架自动写入）
        this.memoryService = new com.javaclaw.memory.MemoryService(
                runtime.getModelFactory(), runtime.getTokenTracker(),
                runtime.getEmbeddingGateway(), taskScope, runtime.getConfig());
        // 嵌入降级可感知：首次嵌入失败弹一次 Toast，避免端点配错时记忆系统静默失效而用户长期不知情
        this.memoryService.setOnEmbeddingDegraded(reason -> {
            com.javaclaw.api.interaction.UserInteractionPort port = ToolConfirmationManager.getPort();
            if (port != null) {
                port.notify(new com.javaclaw.api.interaction.ToastRequest(
                        "记忆嵌入已降级",
                        "长期记忆检索/蒸馏暂不可用：" + reason + "（详见 记忆中心 → 嵌入诊断）"));
            }
        });
        this.memoryService.open(runtime.getWorkspace().globalDataRoot()
                .resolve("memory-stores")
                .resolve(runtime.getWorkspace().workspaceId()));

        // 此后任一初始化步骤失败都必须随本次失败关闭刚打开的记忆库：构造器抛出后本实例
        // 不可达、无人能补 close，悬置的 EclipseStore 文件锁会让同工作区的下一次构造
        // （服务重建的恢复路径）必然撞锁。catch 必定重抛，final 字段的确定性赋值不受影响
        try {
            // 1. 构建 masterToolkit：按分组注册工具，后续按路由激活/禁用
            this.masterToolkit = buildMasterToolkit(runtime);

            // 2. 三个钩子
            this.loopDetectionHook = new LoopDetectionHook(config);
            this.toolFallbackHook = new ToolFallbackHook();
            this.loggingHook = new AgentLoggingHook(runtime.getTraceRecorder());

            // 3. 基础系统提示词（不含动态技能和 MCP，每轮按路由拼接）
            String verificationPrompt = "";
            if (config.isTaskVerificationEnabled()) {
                verificationPrompt = AgentPrompts.ORCHESTRATOR_VERIFICATION_SUFFIX;
                log.info("已启用执行后验证机制");
            }
            this.baseSystemPrompt = AgentPrompts.ORCHESTRATOR_SYS_PROMPT + verificationPrompt;

            // 4. 工具路由器（使用轻量模型，强制关闭 thinking 避免分类调用阻塞数分钟）
            if (config.isToolRoutingEnabled()) {
                this.toolRouter = new ToolRouter(runtime.getModelFactory().createLightChatModel(),
                        runtime.getTokenTracker(), skills.manager(), config,
                        runtime.getJson().mapper());
                log.info("工具路由器已创建（启用状态，thinking 关闭）");
            } else {
                this.toolRouter = null;
                log.info("工具路由已禁用，每轮加载全部工具");
            }

            // 5. GEPA 组件
            if (config.isGepaGoalEnabled()) {
                this.goalManager = new GoalManager(runtime.getModelFactory().createChatModel(),
                        runtime.getTokenTracker(), runtime.getJson().mapper());
                log.info("GEPA 目标管理器已启用");
            } else {
                this.goalManager = null;
                log.info("GEPA 目标分解已禁用");
            }
            // 评估走轻量模型，控制 GEPA 旁路成本
            this.evaluationPipeline = new EvaluationPipeline(
                    runtime.getModelFactory().createLightChatModel(),
                    runtime.getTokenTracker(),
                    config.getGepaEvalInterval(),
                    config.getGepaEvalThreshold(),
                    config.getGepaFeedbackMaxRounds(), runtime.getJson().mapper());
            this.planningEngineAccessor = new PlanEvolverAccessor(
                    config.isGepaPlanAdaptive()
                            ? new PlanEvolver(runtime.getModelFactory().createHighChatModel(),
                                    runtime.getTokenTracker())
                            : null);
            this.executionMonitor = new ExecutionMonitor();
            // 同入参收敛卡死也强制评估（与连续失败共用 forceEvaluate 入口）
            this.executionMonitor.setOnConvergenceStuck(toolName ->
                    log.warn("GEPA 监控：工具 [{}] 同入参收敛，建议评估调整策略", toolName));
            log.info("GEPA 过程评估已启用 — 间隔: {} 次工具调用, 阈值: {}, 最大反馈轮: {}",
                    config.getGepaEvalInterval(), config.getGepaEvalThreshold(),
                    config.getGepaFeedbackMaxRounds());

            // 6. 构建初始 orchestrator（全量工具，供会话恢复等场景）
            // 记忆注入：初始构建无 query，仅注入人格（每轮重建时按 query 检索相关事实/情景）
            this.orchestrator = buildOrchestrator(
                    baseSystemPrompt
                            + memoryService.recall("")
                            + skills.manager().buildSkillCatalogPrompt()
                            + skills.manager().buildEnabledSkillsPrompt()
                            + runtime.getMcpClientManager().buildToolsPrompt());
            log.info("主编排智能体已创建 — name: {}, maxIters: {}, plan: enabled, memory: AutoContext, retry: enabled",
                    AgentConfig.AGENT_NAME, config.getOrchestratorMaxIters());

            // 7. 流式输出选项（thinking 开关决定是否订阅思考事件）——三条编排路径共用
            // ToolkitAssembler.buildStreamOptions 单一来源，勿再本地拷贝构建规则
            this.streamOptions = ToolkitAssembler.buildStreamOptions(config);

            // 8. 事件处理器
            this.eventHandler = new StreamEventHandler(runtime.getJson().mapper());
            this.turnPreparation = new ChatTurnPreparationPipeline(
                    runtime, memoryService, toolRouter, goalManager, streamOptions,
                    this::orchestratorSnapshot, this::rebuildOrchestratorForTurn);
            this.turnCompletion = new ChatTurnCompletionPipeline(
                    runtime, memoryService, configuredCurator, skills, executionMonitor);
            this.sessionStates = new ChatSessionStateStore(
                    runtime, workflowService, memoryService, this::orchestratorSnapshot);
        } catch (RuntimeException | Error e) {
            try {
                memoryService.close();
            } catch (Exception ce) {
                log.warn("构造失败后关闭记忆服务异常: {}", ce.getMessage());
            }
            throw e;
        }

        log.info("========== ChatService 普通模式初始化完成 ==========");
    }

    private Toolkit buildMasterToolkit(AgentRuntime runtime) {
        // 标准工具集统一装配（单一来源见 ToolkitAssembler，三条编排路径共用）；
        // 交互路径注册媒体工具（view_image 弹窗需有人在场）
        Toolkit toolkit = ToolkitAssembler.buildBaseToolkit(
                runtime, runtime.getExpertManager(), true, ToolCallOrigin.INTERACTIVE);

        // 澄清中断工具：模型主动打断本轮并向用户提问——交互路径专属，
        // 无头路径（定时任务/循环）无人在场不注册
        toolkit.registration().tool(clarifyTools).group("clarify").apply();

        log.info("Master Toolkit 已构建 — 工具组: {}", toolkit.getActiveGroups());
        return toolkit;
    }

    /** 内置专家的 toolName 列表（供 Shell 命令模式区分内置/自定义）。 */
    public List<String> builtinAgentNames() {
        return runtime.getExpertManager().getExpertDefs().stream()
                .map(com.javaclaw.agent.expert.ExpertManager.ExpertDef::toolName)
                .toList();
    }

    private ReActAgent buildOrchestrator(String fullSysPrompt) {
        AgentConfig config = runtime.getConfig();
        return ReActAgent.builder()
                .name(AgentConfig.AGENT_NAME)
                .sysPrompt(AgentPrompts.withMandatoryGlobalRules(fullSysPrompt))
                .model(runtime.getModelFactory().createHighChatModel())
                .toolkit(masterToolkit)
                .memory(runtime.getMemoryManager().getOrchestratorMemory())
                .modelExecutionConfig(runtime.getModelExecConfig())
                .maxIters(config.getOrchestratorMaxIters())
                .enablePlan()
                .hooks(List.of(loopDetectionHook, toolFallbackHook, loggingHook))
                .build();
    }

    private ReActAgent orchestratorSnapshot() {
        synchronized (orchestratorLock) {
            return orchestrator;
        }
    }

    // ==================== 公开入口：流式对话 ====================

    /**
     * 流式发送用户消息（支持多媒体附件），以事件流的方式回调结果。
     *
     * <p>流程：视觉预处理 → 工具路由 → 目标分解 → 知识库增强 → 编排智能体调用。
     * 所有中间状态（token 统计、GEPA 评估、循环检测、计划自适应）都通过
     * {@link ConversationEvent} 推送给回调；调用方无需感知底层细节。</p>
     *
     * @param request   用户请求（文本 + 附件）
     * @param callbacks 事件与生命周期回调
     */
    public com.javaclaw.api.conversation.ConversationHandle streamChat(
            ConversationRequest request, ConversationCallbacks callbacks) {
        return conversationRun.start(callbacks,
                guarded -> {
                    runtime.getBrowserManager().activateScope(
                            com.javaclaw.browser.PlaywrightBrowserManager
                                    .conversationScopeId(request.sessionId()));
                    startChatPipeline(request, guarded);
                },
                ignored -> cancelStream(request.sessionId()));
    }

    private void startChatPipeline(ConversationRequest request, ConversationCallbacks callbacks) {
        if (workflowService == null) {
            executeChatPipeline(request, callbacks);
            return;
        }
        workflowService.runSystem(SYSTEM_GRAPH, request.sessionId(),
                com.javaclaw.workflow.service.SystemInvocationState.from(request), callbacks,
                this::executeChatGraphStage);
    }

    private void executeChatPipeline(ConversationRequest request, ConversationCallbacks callbacks) {
        executeChatPipeline(request, callbacks, false, null, null);
    }

    private com.javaclaw.workflow.runtime.NodeResult executeChatGraphStage(
            String stageId, com.javaclaw.workflow.runtime.NodeExecutionContext context) throws Exception {
        ConversationRequest request = com.javaclaw.workflow.service.SystemInvocationState.request(context);
        return switch (stageId) {
            case "vision" -> {
                String processedInput = request.userInput();
                List<File> effectiveAttachments = request.attachments();
                if (runtime.hasImageAttachment(request.attachments())) {
                    String visionDesc = runtime.getVisionPreprocessor()
                            .describe(request.userInput(), request.attachments());
                    if (visionDesc != null) {
                        processedInput = "[附件图片分析]\n" + visionDesc
                                + "\n\n[用户提问]\n" + request.userInput();
                        effectiveAttachments = request.attachments().stream()
                                .filter(file -> !ChatMessage.isImageFile(file)).toList();
                    }
                }
                yield com.javaclaw.workflow.runtime.NodeResult.next(
                        com.javaclaw.workflow.model.StatePatch.builder()
                                .set("system.chat.processedInput", processedInput)
                                .set("system.chat.attachments", effectiveAttachments.stream()
                                        .map(File::getAbsolutePath).toList())
                                .build());
            }
            case "orchestrate" -> {
                String processedInput = context.state().get("system.chat.processedInput")
                        .asText(request.userInput());
                List<File> effectiveAttachments = new ArrayList<>();
                var storedAttachments = context.state().get("system.chat.attachments");
                if (storedAttachments.isArray()) {
                    storedAttachments.forEach(path -> effectiveAttachments.add(new File(path.asText())));
                } else {
                    effectiveAttachments.addAll(request.attachments());
                }
                List<File> preparedAttachments = List.copyOf(effectiveAttachments);
                yield com.javaclaw.workflow.service.SystemPipelineAwaiter.await(
                        context,
                        inner -> executeChatPipeline(request, inner, true,
                                processedInput, preparedAttachments),
                        context.require(ConversationCallbacks.class),
                        this::cancelPipelineStream);
            }
            default -> throw new IllegalArgumentException("未知对话系统阶段: " + stageId);
        };
    }

    private void executeChatPipeline(ConversationRequest request, ConversationCallbacks callbacks,
                                     boolean visionPrepared, String preparedInput,
                                     List<File> preparedAttachments) {
        String userInput = request.userInput();
        List<File> attachments = preparedAttachments == null
                ? request.attachments() : preparedAttachments;
        String initialProcessedInput = preparedInput == null ? userInput : preparedInput;
        this.currentUserInput = userInput == null ? "" : userInput;
        final String correctionSessionKey = ChatSessionStateStore.sessionKey(request.sessionId());
        final String previousAssistantReply = sessionStates.lastReply(request.sessionId());
        final AtomicReference<CorrectionTurnContext> correctionContextRef =
                new AtomicReference<>(CorrectionTurnContext.empty());

        log.info("收到用户消息（普通模式）: {} 字符（正文不记录）", userInput.length());

        // 循环检测：把警告翻译为 LoopDetected 事件
        loopDetectionHook.reset();
        loopDetectionHook.setOnLoopDetected(warning ->
                callbacks.onEvent(new ConversationEvent.LoopDetected(warning)));
        toolFallbackHook.reset();

        // GEPA — 重置过程评估 + 执行监控 + 计划引擎
        evaluationPipeline.reset(userInput);
        executionMonitor.reset();
        if (planningEngineAccessor.get() != null) planningEngineAccessor.get().reset();
        executionMonitor.setOnConsecutiveFailure(toolName -> {
            log.warn("GEPA 执行监控：工具 [{}] 连续失败，提前触发评估", toolName);
            callbacks.onEvent(new ConversationEvent.Hint(
                    "[执行监控] 工具 " + toolName + " 连续失败，正在评估是否需要调整策略..."));
            evaluationPipeline.forceEvaluate(evalResult -> {
                callbacks.onEvent(new ConversationEvent.Evaluation(evalResult));
                var engine = planningEngineAccessor.get();
                if (engine != null && evalResult.isNeedsCorrection()) {
                    engine.evolveFromEvaluation(userInput, evalResult).ifPresent(newPlan ->
                            callbacks.onEvent(new ConversationEvent.Hint(
                                    "[GEPA] 计划已根据评估结果自动调整 (v" + newPlan.getVersion() + ")")));
                }
            });
        });

        // Token 统计
        final int inputCharCount = userInput.length();
        final AtomicInteger outputCharCount = new AtomicInteger(0);
        runtime.getTokenTracker().beginStreaming(inputCharCount);

        // 记忆：收集助手回复文本，结束后异步交给 MemoryService（落情景 + 蒸馏事实）
        // 上限 12000 字符 —— 过长回复对蒸馏来说也只关心结论，无须全文
        // StringBuffer 而非 StringBuilder：追加发生在流线程，而 doFinally 在取消路径上会由
        // 取消方线程读取（停止按钮、澄清中断、下一轮抢占），需要同步以免读到撕裂状态。
        final StringBuffer collectedReply = new StringBuffer();
        final int REPLY_COLLECT_CAP = 12000;
        // 完整回复收集后再做语言判定；逐 token 判断会把代码、URL 或中文流中的英文专名误伤。
        final StringBuffer bufferedModelReply = new StringBuffer();
        final java.util.concurrent.atomic.AtomicBoolean replyPublished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable publishGuardedReply = () -> {
            if (!replyPublished.compareAndSet(false, true)) return;
            String guardedReply = com.javaclaw.util.ChineseOutputGuard
                    .enforceUserVisibleReply(bufferedModelReply.toString());
            if (guardedReply == null || guardedReply.isBlank()) return;
            int remaining = REPLY_COLLECT_CAP - collectedReply.length();
            if (remaining > 0) {
                collectedReply.append(guardedReply, 0, Math.min(remaining, guardedReply.length()));
            }
            callbacks.onEvent(new ConversationEvent.Reply(guardedReply));
        };

        // 领域层回调包装器：拦截 Usage / Reply / ToolResult 做簿记，然后转发给 UI
        final ConversationCallbacks domainCallbacks = new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
                boolean forward = true;
                try {
                    if (event instanceof ConversationEvent.Usage u) {
                        runtime.getTokenTracker().addStreamingUsage(u.inputTokens(), u.outputTokens());
                    } else if (event instanceof ConversationEvent.Reply r) {
                        outputCharCount.addAndGet(r.chunk().length());
                        runtime.getTokenTracker().addStreamingChars(r.chunk().length());
                        bufferedModelReply.append(r.chunk());
                        forward = false;
                    } else if (event instanceof ConversationEvent.ToolResult tr) {
                        // 只有真实工具调用（非子智能体转发）才喂给监控/评估
                        executionMonitor.recordExecution(tr.toolName(), tr.result());
                        evaluationPipeline.recordToolCall(tr.toolName(), tr.result(), evalResult -> {
                            callbacks.onEvent(new ConversationEvent.Evaluation(evalResult));
                            var engine = planningEngineAccessor.get();
                            if (engine != null && evalResult.isNeedsCorrection()) {
                                engine.evolveFromEvaluation(userInput, evalResult).ifPresent(newPlan ->
                                        callbacks.onEvent(new ConversationEvent.Hint(
                                                "[GEPA] 计划已根据评估结果自动调整 (v"
                                                        + newPlan.getVersion() + ")")));
                            }
                        });
                    }
                } catch (Throwable t) {
                    log.error("领域层簿记失败，继续转发事件给 UI", t);
                }
                if (forward) callbacks.onEvent(event);
            }

            @Override public void onTerminal(ConversationOutcome outcome) {
                publishGuardedReply.run();
                callbacks.onTerminal(outcome);
            }
        };

        // 绑定澄清工具回调到本轮 UI 回调；doFinally 中 CAS 解绑，避免跨轮误清。
        // 直接绑到 callbacks（而非 domainCallbacks）：澄清事件不需要 token/执行监控簿记。
        // 同时传入中断器：工具调用后会立即 dispose 编排器订阅，强制终止本轮。
        final Object clarifyBindHandle = clarifyTools.bind(
                callbacks,
                () -> {
                    activeSubscription.dispose();
                    log.info("[澄清] 已 dispose 编排器订阅");
                });

        // doFinally 只清「本轮自己」的订阅引用：上一轮订阅被 set() 顶替时其取消信号会
        // 同步触发 doFinally，无条件 clear() 会抹掉刚 set 进去的新订阅，令停止按钮的
        // dispose 扑空、在途流杀不掉（与 AgentScopeLoopRunner 的 clearIf 同一模式）
        final AtomicReference<Disposable> selfSub = new AtomicReference<>();
        Disposable sub = managedMono(
                        TaskSpec.io("chat-turn-prepare-" + correctionSessionKey),
                        context -> turnPreparation.prepare(
                                userInput, initialProcessedInput, attachments, visionPrepared,
                                callbacks, previousAssistantReply, correctionContextRef))
                .flatMapMany(stream -> stream)
                .doFinally(signal -> {
                    activeSubscription.clearIf(selfSub.get());
                    clarifyTools.unbind(clarifyBindHandle);
                    // 记录本轮实际产出的回复：出错/取消轮同样要更新，否则下一轮显式纠错会把
                    // 两轮之前的回复当成“上一轮”，审计摘录错位。一个字都没生成时保留原值，
                    // 否则会把 loadSession 恢复的上一轮回复清成空串。
                    String finalReply = collectedReply.toString();
                    sessionStates.rememberReply(request.sessionId(), finalReply);
                    // 编排阶段最终标记 — 由 doFinally 兜底，cancel/error 不会漏掉
                    ChatProgressEmitter.emit(callbacks, "orchestrate", "编排执行",
                            signal == reactor.core.publisher.SignalType.ON_ERROR
                                    ? ConversationEvent.Progress.Status.ERROR
                                    : ConversationEvent.Progress.Status.DONE,
                            null);
                    log.info("流式订阅结束 — 信号: {}", signal);
                })
                .subscribe(
                        event -> eventHandler.handleEvent(event, domainCallbacks),
                        error -> {
                            log.error("流式调用发生错误", error);
                            publishGuardedReply.run();
                            runtime.getTokenTracker().recordUsage(inputCharCount, outputCharCount.get());
                            // 出错轮：注入技能记一次失败归因
                            turnCompletion.recordSkillOutcome(turnInjectedSkills, false);
                            callbacks.onTerminal(ConversationOutcome.failed(error));
                        },
                        () -> {
                            log.info("流式输出完成（GEPA 执行轨迹: {} 条）",
                                    executionMonitor.getTraceCount());
                            publishGuardedReply.run();
                            runtime.getTokenTracker().recordUsage(inputCharCount, outputCharCount.get());
                            // 注入技能的轮次成败归因（滑窗成功率达标视为成功）
                            turnCompletion.recordSkillOutcome(
                                    turnInjectedSkills, turnCompletion.successful());
                            String memoryReply = collectedReply.toString();
                            // 疑似复发只记审计、不拦截也不改写：拦错一段正确回复的代价远高于
                            // 它能避免的危害，复发概率由注入的纠错上下文降低。审计让“到底有多
                            // 常复发”变得可测量，将来要不要加防线可以基于数据决定。
                            CorrectionTurnContext correctionContext = correctionContextRef.get();
                            if (correctionContext.hasCorrections()) {
                                CorrectionGuard.findViolation(
                                                memoryReply, correctionContext.corrections())
                                        .ifPresent(violation -> {
                                            memoryService.recordCorrectionGuardViolation(violation);
                                            log.warn("回复中疑似重复已纠正的旧结论（仅记审计，未拦截）: {}",
                                                    violation.wrongClaim());
                                        });
                            }
                            turnCompletion.persist(userInput, memoryReply);
                            callbacks.onTerminal(ConversationOutcome.completed());
                        }
                );
        selfSub.set(sub);
        activeSubscription.set(sub);
    }

    private <T> Mono<T> managedMono(TaskSpec spec, ManagedTask<T> task) {
        return Mono.create(sink -> {
            TaskHandle<T> handle;
            try {
                handle = taskScope.submit(spec, task);
            } catch (Throwable failure) {
                sink.error(failure);
                return;
            }
            sink.onCancel(handle::cancel);
            handle.completion().whenComplete((value, failure) -> {
                if (failure == null) {
                    sink.success(value);
                } else {
                    sink.error(unwrapCompletionFailure(failure));
                }
            });
        });
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 按路由结果和目标上下文重建本轮编排智能体。
     */
    private void rebuildOrchestratorForTurn(
            RoutingResult routing, GoalDecomposition goals) {
        synchronized (orchestratorLock) {
            // 发送前自愈：修复上一轮取消/中断/超时留下的悬空工具调用，
            // 否则带 tool_calls 却缺结果的历史会被网关以
            // "Pending tool calls exist without results" 整体拒绝。
            runtime.getMemoryManager().healOrchestratorDanglingToolCalls();
            List<String> groups = (routing.isFallback() || !routing.hasToolGroups())
                    ? new ArrayList<>(RoutingResult.ALL_TOOL_GROUPS)
                    : new ArrayList<>(routing.toolGroups());
            // 任何路由场景（含全量降级）都强制保留常驻组（clarify/skill/agents/plugins），
            // 单一来源见 ToolkitAssembler.appendResidentGroups（交互路径含 clarify）
            ToolkitAssembler.appendResidentGroups(groups, true);
            masterToolkit.setActiveGroups(groups);
            log.info("本轮活跃工具组: {}", masterToolkit.getActiveGroups());

            // 技能 L0 目录始终常驻（与路由解耦）：模型恒知全部技能存在，避免路由漏判致技能"消失"；
            // 传入本轮可用工具组做条件激活过滤（requires/fallback_for_toolsets）
            String skillCatalog = skills.manager()
                    .buildSkillCatalogPrompt(new java.util.HashSet<>(groups));
            // 技能正文按路由结果预载：命中或降级则全量，否则仅筛选出的技能
            String skillsPrompt;
            if (routing.isAllSkills() || routing.isFallback()) {
                skillsPrompt = skills.manager().buildEnabledSkillsPrompt();
                // 全量注入时信号被稀释，不计入使用统计
                this.turnInjectedSkills = List.of();
            } else {
                skillsPrompt = skills.manager().buildFilteredSkillsPrompt(routing.skillNames());
                // 仅显式路由命中的技能计入使用统计（命中 + 轮次成败归因）
                List<String> hit = routing.skillNames() == null ? List.of() : List.copyOf(routing.skillNames());
                this.turnInjectedSkills = hit;
                for (String name : hit) {
                    skills.usage().recordRouteHit(name);
                }
            }
            // 技能包成组注入（包优先：路由命中包名时整包注入，缺失技能跳过不中断）
            if (routing.hasBundles()
                    && runtime.getConfig().isSkillBundlesEnabled()) {
                StringBuilder bundlePrompts = new StringBuilder();
                for (String bundleName : routing.bundleNames()) {
                    bundlePrompts.append(skills.manager().buildBundlePrompt(bundleName));
                }
                skillsPrompt = skillsPrompt + bundlePrompts;
            }

            String mcpPrompt = (routing.isAllMcp() || routing.isFallback())
                    ? runtime.getMcpClientManager().buildToolsPrompt()
                    : runtime.getMcpClientManager().buildFilteredToolsPrompt(routing.mcpServers());

            String goalPrompt = (goals != null && goals.hasGoals()) ? goals.buildContextPrompt() : "";
            if (!goalPrompt.isEmpty()) {
                log.info("GEPA 目标上下文已注入 — {} 个目标", goals.getGoals().size());
            }

            // 记忆注入：按本轮 query 检索人格 + 相关事实 + 相关情景（替代旧整文件注入）
            String personaContext = memoryService.recall(currentUserInput);
            // 已启用插件贡献的工具清单注入提示词，agent 据此直接 plugin_call_tool 调用
            String pluginPrompt = runtime.getPluginTools().buildToolsPrompt();
            // Recaller 已把相关纠错置于 loaded_context 首部；这里不重复拼接，避免双份 token。
            String fullSysPrompt = baseSystemPrompt + personaContext + skillCatalog + skillsPrompt
                    + mcpPrompt + pluginPrompt + goalPrompt;
            this.orchestrator = buildOrchestrator(fullSysPrompt);
            log.info("本轮编排智能体已重建");
        }
    }

    // ==================== 流式会话控制 ====================

    /**
     * 取消当前正在执行的流式调用。
     *
     * @return true 表示成功取消，false 表示没有活跃的流
     */
    public boolean cancelStream() {
        return conversationRun.cancelActive(
                com.javaclaw.api.conversation.CancellationReason.USER_REQUEST);
    }

    /** 只取消指定会话对应的系统图运行；由本轮句柄捕获调用。 */
    public boolean cancelStream(String sessionId) {
        boolean graphCancelled = workflowService != null
                && workflowService.cancelSystem(SYSTEM_GRAPH.id(), sessionId);
        boolean disposed = cancelPipelineStream();
        return graphCancelled || disposed;
    }

    private boolean cancelPipelineStream() {
        boolean disposed = activeSubscription.dispose();
        if (disposed) {
            log.info("流式调用已手动取消");
        }
        return disposed;
    }

    // ==================== 会话状态持久化 ====================

    /** 清空普通模式全部对话历史（记忆快照 + 钩子状态 + GEPA + PlanNotebook） */
    public void clearHistory() {
        log.info("清空普通模式对话历史");
        runtime.getMemoryManager().clearAll();
        loopDetectionHook.reset();
        clearPlanNotebook();
        evaluationPipeline.reset("");
        executionMonitor.reset();
        var engine = planningEngineAccessor.get();
        if (engine != null) engine.reset();
        sessionStates.clearTrackedReplies();
        log.info("普通模式历史已清空（含记忆快照、钩子状态、计划任务和 GEPA 状态）");
    }

    /** 重置 orchestrator 的 PlanNotebook（通过空会话 loadFrom 清除 currentPlan） */
    private void clearPlanNotebook() {
        var planNotebook = orchestrator.getPlanNotebook();
        if (planNotebook != null && planNotebook.getCurrentPlan() != null) {
            planNotebook.loadFrom(new InMemorySession(), SimpleSessionKey.of("__reset__"));
            log.info("已重置编排器计划任务");
        }
    }

    public void saveSession(String sessionId) {
        sessionStates.save(sessionId);
    }

    public void loadSession(String sessionId) {
        sessionStates.load(sessionId);
    }

    public void deleteSession(String sessionId) {
        sessionStates.delete(sessionId);
    }

    /** 记忆服务（供记忆中心 UI 查看/编辑）。 */
    public com.javaclaw.memory.MemoryService getMemoryService() {
        return memoryService;
    }

    /**
     * 获取当前规划状态的 Markdown 文本（PlanNotebook 内容）。
     * 没有活动规划则返回 null。
     */
    public String getCurrentPlanMarkdown() {
        var planNotebook = orchestrator.getPlanNotebook();
        if (planNotebook != null && planNotebook.getCurrentPlan() != null) {
            return planNotebook.getCurrentPlan().toMarkdown(true);
        }
        return null;
    }

    // ==================== 外部访问 ====================

    /** 设置循环检测的交互式处理器（由 UI 层注入，决定继续或终止） */
    public void setLoopInteractiveHandler(LoopDetectionHook.LoopInteractiveHandler handler) {
        loopDetectionHook.setLoopInteractiveHandler(handler);
    }

    /** 获取 GEPA 执行监控器（供 TaskManager 写入执行摘要） */
    public ExecutionMonitor getExecutionMonitor() {
        return executionMonitor;
    }

    /** 获取 GEPA 计划演进器（可能为 null，取决于配置） */
    public PlanEvolver getPlanningEngine() {
        return planningEngineAccessor.get();
    }

    // ==================== 生命周期 ====================

    /** 关闭服务：取消活跃流 + 关闭记忆库（释放 EclipseStore 锁与写线程）；其余共享资源由 AgentRuntime 统一关闭 */
    public void shutdown() {
        log.info("正在关闭 ChatService...");
        conversationRun.cancelActive(
                com.javaclaw.api.conversation.CancellationReason.SHUTDOWN);
        try {
            memoryService.close();
        } catch (Exception e) {
            log.warn("关闭记忆服务异常: {}", e.getMessage());
        }
        log.info("ChatService 已关闭");
    }

    /**
     * 持有 {@link PlanEvolver} 的轻量包装，方便在 lambda 中判空读取。
     * 实例在构造时一次性决定，不会变动。
     */
    private static final class PlanEvolverAccessor {
        private final PlanEvolver delegate;

        PlanEvolverAccessor(PlanEvolver delegate) {
            this.delegate = delegate;
        }

        PlanEvolver get() {
            return delegate;
        }
    }
}
