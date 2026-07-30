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
import com.javaclaw.chat.ChatMessage;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.memory.correction.CorrectionGuard;
import com.javaclaw.memory.correction.CorrectionTurnContext;
import com.javaclaw.prompt.AgentPrompts;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.util.AtomicDisposable;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.session.InMemorySession;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    /** 技能蒸馏器（程序性记忆：轮后从执行轨迹蒸馏可沉淀的工作流经验，借鉴 hermes-agent） */
    private final com.javaclaw.skill.curation.SkillCurator skillCurator;

    /** 本轮显式路由注入的技能名（全量注入时为空列表，不计入使用统计） */
    private volatile List<String> turnInjectedSkills = List.of();

    /** 本轮用户输入（供按 query 检索相关记忆注入；每轮重建编排器时读取） */
    private volatile String currentUserInput = "";

    /** 会话 → 最近一次实际交付给用户的助手回复（显式纠错需要定位上一轮目标）。 */
    private final java.util.concurrent.ConcurrentMap<String, String> lastAssistantReplies =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 构造并初始化普通模式服务。
     *
     * @param runtime 共享基础设施
     */
    public ChatService(AgentRuntime runtime) {
        this(runtime, null);
    }

    public ChatService(AgentRuntime runtime, com.javaclaw.workflow.service.WorkflowService workflowService) {
        this.runtime = runtime;
        this.workflowService = workflowService;
        if (workflowService != null) workflowService.systemGraphs().register(SYSTEM_GRAPH);
        AgentConfig config = AgentConfig.getInstance();
        log.info("========== 初始化 ChatService 普通模式 ==========");

        // 0. 记忆服务：打开当前工作区的 EclipseStore 记忆库（人格默认骨架自动写入）
        this.memoryService = new com.javaclaw.memory.MemoryService(
                runtime.getModelFactory(), runtime.getTokenTracker(),
                runtime.getEmbeddingGateway());
        // 嵌入降级可感知：首次嵌入失败弹一次 Toast，避免端点配错时记忆系统静默失效而用户长期不知情
        this.memoryService.setOnEmbeddingDegraded(reason -> {
            com.javaclaw.api.interaction.UserInteractionPort port = ToolConfirmationManager.getPort();
            if (port != null) {
                port.notify(new com.javaclaw.api.interaction.ToastRequest(
                        "记忆嵌入已降级",
                        "长期记忆检索/蒸馏暂不可用：" + reason + "（详见 记忆中心 → 嵌入诊断）"));
            }
        });
        com.javaclaw.config.WorkspaceManager workspaceManager =
                com.javaclaw.config.WorkspaceManager.getInstance();
        this.memoryService.open(workspaceManager.getGlobalDataPath()
                .resolve("memory-stores")
                .resolve(workspaceManager.getCurrentWorkspaceId()));

        // 此后任一初始化步骤失败都必须随本次失败关闭刚打开的记忆库：构造器抛出后本实例
        // 不可达、无人能补 close，悬置的 EclipseStore 文件锁会让同工作区的下一次构造
        // （服务重建的恢复路径）必然撞锁。catch 必定重抛，final 字段的确定性赋值不受影响
        try {
            // 技能蒸馏器（程序性记忆）：提案队列同时接收 skill_manage 主动路径与本蒸馏器的兜底路径，
            // 两路按变更指纹统一去重；auto 模式 Toast 经 ToolConfirmationManager 注入的交互端口
            this.skillCurator = new com.javaclaw.skill.curation.SkillCurator(
                    runtime.getModelFactory(),
                    runtime.getTokenTracker(),
                    com.javaclaw.skill.curation.SkillProposalQueue.getInstance(),
                    ToolConfirmationManager::getPort);
            com.javaclaw.skill.SkillManageTools.setProposalSink(
                    com.javaclaw.skill.curation.SkillProposalQueue.getInstance());

            // 1. 构建 masterToolkit：按分组注册工具，后续按路由激活/禁用
            this.masterToolkit = buildMasterToolkit(runtime);

            // 2. 三个钩子
            this.loopDetectionHook = new LoopDetectionHook();
            this.toolFallbackHook = new ToolFallbackHook();
            this.loggingHook = new AgentLoggingHook();

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
                        runtime.getTokenTracker());
                log.info("工具路由器已创建（启用状态，thinking 关闭）");
            } else {
                this.toolRouter = null;
                log.info("工具路由已禁用，每轮加载全部工具");
            }

            // 5. GEPA 组件
            if (config.isGepaGoalEnabled()) {
                this.goalManager = new GoalManager(runtime.getModelFactory().createChatModel(),
                        runtime.getTokenTracker());
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
                    config.getGepaFeedbackMaxRounds());
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
                            + SkillManager.getInstance().buildSkillCatalogPrompt()
                            + SkillManager.getInstance().buildEnabledSkillsPrompt()
                            + runtime.getMcpClientManager().buildToolsPrompt());
            log.info("主编排智能体已创建 — name: {}, maxIters: {}, plan: enabled, memory: AutoContext, retry: enabled",
                    AgentConfig.AGENT_NAME, config.getOrchestratorMaxIters());

            // 7. 流式输出选项（thinking 开关决定是否订阅思考事件）——三条编排路径共用
            // ToolkitAssembler.buildStreamOptions 单一来源，勿再本地拷贝构建规则
            this.streamOptions = ToolkitAssembler.buildStreamOptions(config);

            // 8. 事件处理器
            this.eventHandler = new StreamEventHandler();
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
        AgentConfig config = AgentConfig.getInstance();
        return ReActAgent.builder()
                .name(AgentConfig.AGENT_NAME)
                .sysPrompt(fullSysPrompt)
                .model(runtime.getModelFactory().createHighChatModel())
                .toolkit(masterToolkit)
                .memory(runtime.getMemoryManager().getOrchestratorMemory())
                .modelExecutionConfig(runtime.getModelExecConfig())
                .maxIters(config.getOrchestratorMaxIters())
                .enablePlan()
                .hooks(List.of(loopDetectionHook, toolFallbackHook, loggingHook))
                .build();
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
                guarded -> startChatPipeline(request, guarded),
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
        final String correctionSessionKey = correctionSessionKey(request.sessionId());
        final String previousAssistantReply =
                lastAssistantReplies.getOrDefault(correctionSessionKey, "");
        final AtomicReference<CorrectionTurnContext> correctionContextRef =
                new AtomicReference<>(CorrectionTurnContext.empty());

        log.info("收到用户消息（普通模式）: {}", userInput);

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
        final StringBuilder collectedReply = new StringBuilder();
        // 仅在纠错守卫启用时保存完整草稿；collectedReply 仍维持原有 12k 记忆上限。
        final StringBuilder guardedReply = new StringBuilder();
        final int REPLY_COLLECT_CAP = 12000;

        // 领域层回调包装器：拦截 Usage / Reply / ToolResult 做簿记，然后转发给 UI
        final ConversationCallbacks domainCallbacks = new ConversationCallbacks() {
            @Override
            public void onEvent(ConversationEvent event) {
                try {
                    if (event instanceof ConversationEvent.Usage u) {
                        runtime.getTokenTracker().addStreamingUsage(u.inputTokens(), u.outputTokens());
                    } else if (event instanceof ConversationEvent.Reply r) {
                        outputCharCount.addAndGet(r.chunk().length());
                        runtime.getTokenTracker().addStreamingChars(r.chunk().length());
                        if (collectedReply.length() < REPLY_COLLECT_CAP) {
                            int remaining = REPLY_COLLECT_CAP - collectedReply.length();
                            collectedReply.append(r.chunk(), 0,
                                    Math.min(remaining, r.chunk().length()));
                        }
                        // 相关纠错轮次先缓冲最终回复，完成时经 CorrectionGuard 审核后一次性交付；
                        // 流式片段一旦发给 UI，就无法在发现重复旧错误后撤回。
                        if (correctionContextRef.get().requiresReplyGuard()) {
                            guardedReply.append(r.chunk());
                            return;
                        }
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
                callbacks.onEvent(event);
            }

            @Override public void onTerminal(ConversationOutcome outcome) {
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

        // 管道顺序：视觉预处理 → 意图识别（工具路由）→ 目标分解 → 知识库检索 → 上下文整理 → 编排执行
        // 每个阶段都通过 ConversationEvent.Progress 向 UI 实时报告 RUNNING / DONE / SKIPPED 状态
        final AtomicReference<List<File>> effectiveAttachments = new AtomicReference<>(attachments);
        // doFinally 只清「本轮自己」的订阅引用：上一轮订阅被 set() 顶替时其取消信号会
        // 同步触发 doFinally，无条件 clear() 会抹掉刚 set 进去的新订阅，令停止按钮的
        // dispose 扑空、在途流杀不掉（与 AgentScopeLoopRunner 的 clearIf 同一模式）
        final AtomicReference<Disposable> selfSub = new AtomicReference<>();
        Disposable sub = Mono.fromCallable(() -> {
                    String processedInput = initialProcessedInput;

                    // ── 阶段 0：显式纠错（同步 durable-first，先于本轮记忆召回） ──
                    CorrectionTurnContext correctionContext =
                            memoryService.prepareCorrectionTurn(userInput, previousAssistantReply);
                    correctionContextRef.set(correctionContext);
                    if (correctionContext.newlyApplied() != null) {
                        emitProgress(callbacks, "correction", "纠错记忆",
                                ConversationEvent.Progress.Status.DONE,
                                correctionContext.newlyApplied().status
                                        == com.javaclaw.memory.model.CorrectionRecord.Status.ACTIVE
                                        ? "已更新长期事实" : "已标记争议，等待核验");
                        callbacks.onEvent(new ConversationEvent.Hint(
                                "[记忆] 已记录用户显式纠错，本轮优先采用纠错上下文"));
                    } else {
                        emitProgress(callbacks, "correction", "纠错记忆",
                                ConversationEvent.Progress.Status.SKIPPED, "未检测到显式纠错");
                    }

                    // ── 阶段 1：视觉预处理（仅当含图片附件） ──
                    if (!visionPrepared && runtime.hasImageAttachment(attachments)) {
                        emitProgress(callbacks, "vision", "视觉预处理",
                                ConversationEvent.Progress.Status.RUNNING, "正在分析图片内容…");
                        callbacks.onEvent(new ConversationEvent.Hint("[视觉] 正在分析图片内容..."));
                        String visionDesc = runtime.getVisionPreprocessor()
                                .describe(userInput, attachments);
                        if (visionDesc != null) {
                            processedInput = "[附件图片分析]\n" + visionDesc
                                    + "\n\n[用户提问]\n" + userInput;
                            List<File> remaining = new ArrayList<>();
                            for (File f : attachments) {
                                if (!ChatMessage.isImageFile(f)) remaining.add(f);
                            }
                            effectiveAttachments.set(remaining);
                            log.info("视觉预处理成功，剩余附件: {}", remaining.size());
                            emitProgress(callbacks, "vision", "视觉预处理",
                                    ConversationEvent.Progress.Status.DONE,
                                    summarize(visionDesc, 60));
                        } else {
                            log.info("视觉预处理未产生文本（失败/超时），保留原附件直传模型");
                            emitProgress(callbacks, "vision", "视觉预处理",
                                    ConversationEvent.Progress.Status.SKIPPED,
                                    "未生成描述，原图直传");
                        }
                    } else if (!visionPrepared) {
                        emitProgress(callbacks, "vision", "视觉预处理",
                                ConversationEvent.Progress.Status.SKIPPED, "无图片附件");
                    }

                    // ── 阶段 2：意图识别（工具路由） ──
                    emitProgress(callbacks, "intent", "意图识别",
                            ConversationEvent.Progress.Status.RUNNING, "分析所需工具…");
                    RoutingResult routing = routeTools(processedInput);
                    if (toolRouter == null) {
                        emitProgress(callbacks, "intent", "意图识别",
                                ConversationEvent.Progress.Status.SKIPPED, "工具路由已禁用");
                    } else if (routing.isFallback()) {
                        emitProgress(callbacks, "intent", "意图识别",
                                ConversationEvent.Progress.Status.DONE, "降级为全量工具");
                    } else {
                        emitProgress(callbacks, "intent", "意图识别",
                                ConversationEvent.Progress.Status.DONE,
                                describeRouting(routing));
                    }

                    // ── 阶段 3：目标分解（GoalManager） ──
                    GoalDecomposition goals;
                    if (goalManager != null) {
                        emitProgress(callbacks, "goal", "目标分解",
                                ConversationEvent.Progress.Status.RUNNING, "拆解用户目标…");
                        goals = goalManager.decompose(processedInput);
                        if (goals != null && goals.hasGoals()) {
                            emitProgress(callbacks, "goal", "目标分解",
                                    ConversationEvent.Progress.Status.DONE,
                                    "拆解为 " + goals.getGoals().size() + " 个目标");
                        } else {
                            emitProgress(callbacks, "goal", "目标分解",
                                    ConversationEvent.Progress.Status.SKIPPED, "无需拆解");
                        }
                    } else {
                        goals = null;
                        emitProgress(callbacks, "goal", "目标分解",
                                ConversationEvent.Progress.Status.SKIPPED, "GEPA 目标分解未启用");
                    }
                    rebuildOrchestratorForTurn(routing, goals);

                    // ── 阶段 4：知识库检索（RAG） ──
                    emitProgress(callbacks, "rag", "知识库检索",
                            ConversationEvent.Progress.Status.RUNNING, "检索相关资料…");
                    int beforeLen = processedInput.length();
                    String enrichedInput = runtime.enrichWithKnowledge(processedInput);
                    if (enrichedInput.length() > beforeLen) {
                        emitProgress(callbacks, "rag", "知识库检索",
                                ConversationEvent.Progress.Status.DONE,
                                "已注入 " + (enrichedInput.length() - beforeLen) + " 字符上下文");
                    } else {
                        emitProgress(callbacks, "rag", "知识库检索",
                                ConversationEvent.Progress.Status.SKIPPED,
                                "未启用或未选中文档");
                    }
                    return enrichedInput;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(enrichedInput -> {
                    // ── 阶段 5：上下文整理（必要时压缩） ──
                    emitProgress(callbacks, "memory", "上下文整理",
                            ConversationEvent.Progress.Status.RUNNING, "检查上下文预算…");
                    boolean fit = runtime.getMemoryManager().ensureContextBudget(enrichedInput.length(), 4096);
                    if (!fit) {
                        log.warn("上下文窗口接近上限，已触发主动压缩");
                        callbacks.onEvent(new ConversationEvent.Hint(
                                "[提示] 会话历史较长，已自动压缩上下文以保证回复质量"));
                        emitProgress(callbacks, "memory", "上下文整理",
                                ConversationEvent.Progress.Status.DONE, "已自动压缩历史");
                    } else {
                        emitProgress(callbacks, "memory", "上下文整理",
                                ConversationEvent.Progress.Status.DONE, "无需压缩");
                    }

                    // ── 阶段 6：内容构建 + 编排执行 ──
                    emitProgress(callbacks, "build", "内容构建",
                            ConversationEvent.Progress.Status.RUNNING, "组装多模态消息…");
                    Msg userMsg = runtime.buildUserMsg(enrichedInput, effectiveAttachments.get());
                    emitProgress(callbacks, "build", "内容构建",
                            ConversationEvent.Progress.Status.DONE, null);

                    log.info("正在调用编排智能体...");
                    emitProgress(callbacks, "orchestrate", "编排执行",
                            ConversationEvent.Progress.Status.RUNNING, "调用主智能体…");
                    ReActAgent snapshot;
                    synchronized (orchestratorLock) {
                        snapshot = orchestrator;
                    }
                    return snapshot.stream(userMsg, streamOptions);
                })
                .doFinally(signal -> {
                    activeSubscription.clearIf(selfSub.get());
                    clarifyTools.unbind(clarifyBindHandle);
                    // 编排阶段最终标记 — 由 doFinally 兜底，cancel/error 不会漏掉
                    emitProgress(callbacks, "orchestrate", "编排执行",
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
                            runtime.getTokenTracker().recordUsage(inputCharCount, outputCharCount.get());
                            // 出错轮：注入技能记一次失败归因
                            recordSkillTurnOutcome(false);
                            callbacks.onTerminal(ConversationOutcome.failed(error));
                        },
                        () -> {
                            log.info("流式输出完成（GEPA 执行轨迹: {} 条）",
                                    executionMonitor.getTraceCount());
                            runtime.getTokenTracker().recordUsage(inputCharCount, outputCharCount.get());
                            // 注入技能的轮次成败归因（滑窗成功率达标视为成功）
                            recordSkillTurnOutcome(isTurnSuccessful());
                            CorrectionTurnContext correctionContext = correctionContextRef.get();
                            String deliveredReply = correctionContext.requiresReplyGuard()
                                    ? guardedReply.toString() : collectedReply.toString();
                            if (correctionContext.requiresReplyGuard()) {
                                var violation = CorrectionGuard.findViolation(
                                        deliveredReply, correctionContext.corrections());
                                if (violation.isPresent()) {
                                    memoryService.recordCorrectionGuardViolation(violation.get());
                                    deliveredReply = CorrectionGuard.safeFallback(violation.get());
                                    runtime.getMemoryManager()
                                            .replaceLastAssistantReply(deliveredReply);
                                    callbacks.onEvent(new ConversationEvent.Hint(
                                            "[纠错保护] 已拦截模型草稿中重复出现的旧结论"));
                                    log.warn("纠错守卫已拦截重复旧结论: {}",
                                            violation.get().wrongClaim());
                                }
                                // 纠错相关轮次此前未把 Reply 增量转发给 UI；审核通过后一次性交付。
                                if (!deliveredReply.isBlank()) {
                                    callbacks.onEvent(new ConversationEvent.Reply(deliveredReply));
                                }
                            }
                            String memoryReply = deliveredReply.length() <= REPLY_COLLECT_CAP
                                    ? deliveredReply
                                    : deliveredReply.substring(0, REPLY_COLLECT_CAP);
                            lastAssistantReplies.put(correctionSessionKey, memoryReply);
                            // 记忆：轮后异步落情景 + 向量去重蒸馏事实（替代旧 distill/consolidate 批处理）
                            memoryService.rememberTurn("chat", userInput, memoryReply, null);
                            // 技能蒸馏（程序性记忆）：达门槛时从执行轨迹蒸馏可沉淀的工作流经验
                            skillCurator.distillFromChatTurn(userInput, memoryReply,
                                            executionMonitor.getTraces(), executionMonitor.successRate())
                                    .subscribe();
                            callbacks.onTerminal(ConversationOutcome.completed());
                        }
                );
        selfSub.set(sub);
        activeSubscription.set(sub);
    }

    /**
     * 判定本轮是否成功：执行轨迹滑窗成功率达到 skill.evolution.success.threshold；
     * 无工具调用的纯对话轮视为成功。
     */
    private boolean isTurnSuccessful() {
        if (executionMonitor.getTraceCount() == 0) {
            return true;
        }
        return executionMonitor.successRate()
                >= AgentConfig.getInstance().getSkillEvolutionSuccessThreshold();
    }

    /** 把本轮注入技能的成败归因写入使用统计（异常不抛出，避免阻断主流程） */
    private void recordSkillTurnOutcome(boolean success) {
        try {
            List<String> injected = turnInjectedSkills;
            if (injected != null && !injected.isEmpty()) {
                com.javaclaw.skill.SkillUsageTracker.getInstance().recordTurnOutcome(injected, success);
            }
        } catch (Exception e) {
            log.debug("记录技能轮次成败失败（忽略）: {}", e.getMessage());
        }
    }

    /** 发送一个进度事件（异常不抛出，避免阻断主流程） */
    private void emitProgress(ConversationCallbacks callbacks, String stageId, String label,
                              ConversationEvent.Progress.Status status, String detail) {
        try {
            callbacks.onEvent(new ConversationEvent.Progress(stageId, label, status, detail));
        } catch (Throwable t) {
            log.debug("发送 Progress 事件失败（忽略）: {}/{} — {}", stageId, status, t.getMessage());
        }
    }

    /** 把路由结果概要成一段简短文案给 UI 展示 */
    private String describeRouting(RoutingResult routing) {
        int groupCount = routing.toolGroups() == null ? 0 : routing.toolGroups().size();
        int skillCount = routing.skillNames() == null ? 0 : routing.skillNames().size();
        StringBuilder sb = new StringBuilder();
        sb.append("命中 ").append(groupCount).append(" 个工具组");
        if (skillCount > 0) {
            sb.append("，").append(skillCount).append(" 项技能");
        }
        return sb.toString();
    }

    /** 截断长文本用于详情展示 */
    private String summarize(String text, int maxLen) {
        if (text == null) return null;
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= maxLen ? oneLine : oneLine.substring(0, maxLen) + "…";
    }

    /**
     * 执行工具路由：分析用户意图，返回需要的工具组/技能/MCP。
     * 路由器禁用或路由失败时返回全量结果（降级为原有行为）。
     */
    private RoutingResult routeTools(String userInput) {
        if (toolRouter == null) {
            return RoutingResult.fallbackAll();
        }
        try {
            return toolRouter.route(userInput);
        } catch (Exception e) {
            log.warn("工具路由异常，降级为全量: {}", e.getMessage());
            return RoutingResult.fallbackAll();
        }
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
            String skillCatalog = SkillManager.getInstance()
                    .buildSkillCatalogPrompt(new java.util.HashSet<>(groups));
            // 技能正文按路由结果预载：命中或降级则全量，否则仅筛选出的技能
            String skillsPrompt;
            if (routing.isAllSkills() || routing.isFallback()) {
                skillsPrompt = SkillManager.getInstance().buildEnabledSkillsPrompt();
                // 全量注入时信号被稀释，不计入使用统计
                this.turnInjectedSkills = List.of();
            } else {
                skillsPrompt = SkillManager.getInstance().buildFilteredSkillsPrompt(routing.skillNames());
                // 仅显式路由命中的技能计入使用统计（命中 + 轮次成败归因）
                List<String> hit = routing.skillNames() == null ? List.of() : List.copyOf(routing.skillNames());
                this.turnInjectedSkills = hit;
                for (String name : hit) {
                    com.javaclaw.skill.SkillUsageTracker.getInstance().recordRouteHit(name);
                }
            }
            // 技能包成组注入（包优先：路由命中包名时整包注入，缺失技能跳过不中断）
            if (routing.hasBundles()
                    && com.javaclaw.config.AgentConfig.getInstance().isSkillBundlesEnabled()) {
                StringBuilder bundlePrompts = new StringBuilder();
                for (String bundleName : routing.bundleNames()) {
                    bundlePrompts.append(SkillManager.getInstance().buildBundlePrompt(bundleName));
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
            String pluginPrompt = com.javaclaw.plugin.PluginManager.getInstance().buildToolsPrompt();
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
        lastAssistantReplies.clear();
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
        try {
            List<Msg> msgs = orchestrator.getMemory().getMessages();
            String json = io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(msgs);
            memoryService.checkpoint(sessionId, json);
            log.info("会话已检查点入记忆库: {} ({} 条消息)", sessionId, msgs.size());
        } catch (Exception e) {
            log.error("保存会话检查点失败: {}", sessionId, e);
        }
    }

    public void loadSession(String sessionId) {
        try {
            com.javaclaw.memory.model.AgentCheckpoint ckpt = memoryService.loadCheckpoint(sessionId);
            if (ckpt == null || ckpt.messagesJson == null || ckpt.messagesJson.isBlank()) {
                log.info("无会话检查点，使用空白状态: {}", sessionId);
                return;
            }
            List<Msg> msgs = io.agentscope.core.util.JsonUtils.getJsonCodec()
                    .fromJson(ckpt.messagesJson, new com.fasterxml.jackson.core.type.TypeReference<List<Msg>>() {});
            io.agentscope.core.memory.Memory mem = orchestrator.getMemory();
            mem.clear();
            for (Msg m : msgs) {
                mem.addMessage(m);
            }
            for (int i = msgs.size() - 1; i >= 0; i--) {
                Msg m = msgs.get(i);
                if (m.getRole() == io.agentscope.core.message.MsgRole.ASSISTANT
                        && m.getTextContent() != null
                        && !m.getTextContent().isBlank()) {
                    lastAssistantReplies.put(
                            correctionSessionKey(sessionId), m.getTextContent());
                    break;
                }
            }
            // 恢复后自愈悬空工具调用（上次可能停在 tool_call 与结果之间）
            runtime.getMemoryManager().healDanglingToolCalls(mem, "orchestrator");
            log.info("会话已从记忆库检查点恢复: {} ({} 条消息)", sessionId, msgs.size());
        } catch (Exception e) {
            log.warn("恢复会话检查点失败（使用空白状态）: {}", sessionId, e);
        }
    }

    public void deleteSession(String sessionId) {
        memoryService.deleteCheckpoint(sessionId);
        lastAssistantReplies.remove(correctionSessionKey(sessionId));
    }

    private static String correctionSessionKey(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "__default__" : sessionId;
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
