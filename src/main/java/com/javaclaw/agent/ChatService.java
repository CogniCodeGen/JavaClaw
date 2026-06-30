package com.javaclaw.agent;

import com.javaclaw.agent.evaluation.EvaluationPipeline;
import com.javaclaw.agent.execution.ExecutionMonitor;
import com.javaclaw.agent.expert.DynamicTaskTool;
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
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.chat.ChatMessage;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.prompt.AgentPrompts;
import com.javaclaw.mcp.McpTools;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.util.AtomicDisposable;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
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

    /** 共享基础设施容器 */
    private final AgentRuntime runtime;

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

    /**
     * 构造并初始化普通模式服务。
     *
     * @param runtime 共享基础设施
     */
    public ChatService(AgentRuntime runtime) {
        this.runtime = runtime;
        AgentConfig config = AgentConfig.getInstance();
        log.info("========== 初始化 ChatService 普通模式 ==========");

        // 0. 记忆服务：打开当前工作区的 EclipseStore 记忆库（人格默认骨架自动写入）
        this.memoryService = new com.javaclaw.memory.MemoryService(
                runtime.getModelFactory(), runtime.getTokenTracker());
        this.memoryService.open(
                com.javaclaw.config.WorkspaceManager.getInstance()
                        .getCurrentWorkspacePath().resolve("data").resolve("memory-store"));

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

        // 7. 配置流式输出选项（含/不含思考事件）
        StreamOptions.Builder streamBuilder = StreamOptions.builder().incremental(true);
        if (config.isThinkingEnabled()) {
            streamBuilder.includeReasoningChunk(true)
                    .includeReasoningResult(false)
                    .eventTypes(EventType.REASONING, EventType.TOOL_RESULT,
                            EventType.HINT, EventType.AGENT_RESULT);
        } else {
            streamBuilder.eventTypes(EventType.TOOL_RESULT,
                    EventType.HINT, EventType.AGENT_RESULT);
        }
        this.streamOptions = streamBuilder.build();

        // 8. 事件处理器
        this.eventHandler = new StreamEventHandler();

        log.info("========== ChatService 普通模式初始化完成 ==========");
    }

    private Toolkit buildMasterToolkit(AgentRuntime runtime) {
        Toolkit toolkit = new Toolkit();

        for (String group : RoutingResult.ALL_TOOL_GROUPS) {
            toolkit.createToolGroup(group, group, true);
        }

        var expertManager = runtime.getExpertManager();

        for (var def : expertManager.getExpertDefs()) {
            var tool = expertManager.getAllTools().stream()
                    .filter(t -> t.getName().equals(def.toolName()))
                    .findFirst().orElse(null);
            if (tool != null) {
                toolkit.registration().agentTool(tool).group(def.groupName()).apply();
            }
        }

        // 自定义/动态智能体统一进常驻 agents 组：原先每个自定义专家用自己的 toolName 作组名，
        // 既不在 ALL_TOOL_GROUPS 也不在 ALWAYS_ACTIVE_GROUPS，导致永不被路由激活。归入常驻 agents 组后
        // 所有自定义智能体每轮可用（含对话中新建的、供定时任务调用的）。
        toolkit.createToolGroup("agents", "agents", true);
        for (var tool : expertManager.getAllTools()) {
            if (!toolkit.getToolNames().contains(tool.getName())) {
                toolkit.registration().agentTool(tool).group("agents").apply();
            }
        }

        toolkit.registration().agentTool(runtime.getKnowledgeExpert().getTool())
                .group("knowledge").apply();

        DynamicTaskTool dynamicTaskTool = new DynamicTaskTool(
                runtime.getModelFactory(), runtime.getMemoryManager(),
                runtime.getCapabilityTools());
        toolkit.registration().tool(dynamicTaskTool).group("dynamic_task").apply();

        // MCP 工具始终注册：McpTools 内部对 McpClientManager 是动态引用，
        // 后续通过设置面板热启动新 server 时无需重建 toolkit；启动时没有活跃 server
        // 也不影响绑定，工具调用时会返回明确的"无可用服务器"错误。
        McpTools mcpTools = new McpTools(runtime.getMcpClientManager());
        toolkit.registration().tool(mcpTools).group("mcp").apply();
        log.info("MCP 工具已注册到编排器（活跃服务器数: {}）",
                runtime.getMcpClientManager().getAllTools().size());

        // 插件工具桥：把已启用插件贡献的工具暴露给编排器（host → plugin 方向）。
        // 独立成组并每轮强制激活（见 rebuildOrchestratorForTurn），与路由解耦，避免动态工具被路由漏判。
        toolkit.createToolGroup("plugins", "plugins", true);
        toolkit.registration().tool(new com.javaclaw.plugin.PluginTools()).group("plugins").apply();
        log.info("插件工具桥已注册到编排器（始终可用）");

        // 澄清中断工具：模型主动打断本轮并向用户提问；任何路由场景都保留可用
        toolkit.registration().tool(clarifyTools).group("clarify").apply();
        log.info("澄清工具已注册到编排器（始终可用）");

        // 技能按需读取工具（skill_read）：渐进式暴露的 L2 拉取入口，配合常驻的 L1 技能目录使用；
        // 独立成组且不在 ALL_TOOL_GROUPS 中，由 ALWAYS_ACTIVE_GROUPS 每轮强制激活
        toolkit.createToolGroup("skill", "skill", true);
        toolkit.registration().tool(new com.javaclaw.skill.SkillTools()).group("skill").apply();
        // 技能自管理工具（skill_create/patch/edit/delete/write_file/remove_file）：
        // 智能体的程序性记忆写入口，受 skill.evolution.mode 三态闸门约束，与 skill_read 同组常驻
        toolkit.registration().tool(new com.javaclaw.skill.SkillManageTools()).group("skill").apply();
        // JShell 执行工具（jshell_exec/jshell_run_script）：技能 scripts/ 脚本的执行通道，
        // 常驻以保证技能指令引用的脚本不受路由漏判影响；两工具均为 CONFIRM 级
        toolkit.registration().tool(new com.javaclaw.system.JShellTools()).group("skill").apply();
        log.info("技能读取/自管理/JShell 执行工具已注册到编排器（始终可用）");

        // 内置能力工具：长任务管理（task_manage）+ 定时工作管理（schedule），按路由激活；
        // 智能体管理（agent_*）进常驻 agents 组，创建后热注册的新智能体即在同组。
        toolkit.registration().tool(new com.javaclaw.task.sdd.run.SddTaskManageTools())
                .group("task_manage").apply();
        toolkit.registration().tool(new com.javaclaw.schedule.ScheduleTools())
                .group("schedule").apply();
        toolkit.registration().tool(new com.javaclaw.agent.expert.ExpertManageTools(expertManager))
                .group("agents").apply();
        log.info("长任务/定时/智能体列举工具已注册到编排器");

        // 媒体工具：图片查看（view_image）+ 图片/PDF OCR（ocr_recognize），按路由激活
        toolkit.registration().tool(new com.javaclaw.media.MediaTools(runtime.getVisionPreprocessor()))
                .group("media").apply();
        log.info("媒体工具（图片查看 / OCR）已注册到编排器");

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
    public void streamChat(ConversationRequest request, ConversationCallbacks callbacks) {
        String userInput = request.userInput();
        List<File> attachments = request.attachments();
        this.currentUserInput = userInput == null ? "" : userInput;

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
                            collectedReply.append(r.chunk());
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

            @Override public void onComplete() { callbacks.onComplete(); }
            @Override public void onError(Throwable error) { callbacks.onError(error); }
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
        Disposable sub = Mono.fromCallable(() -> {
                    String processedInput = userInput;

                    // ── 阶段 1：视觉预处理（仅当含图片附件） ──
                    if (runtime.hasImageAttachment(attachments)) {
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
                    } else {
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
                    activeSubscription.clear();
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
                            callbacks.onError(error);
                        },
                        () -> {
                            log.info("流式输出完成（GEPA 执行轨迹: {} 条）",
                                    executionMonitor.getTraceCount());
                            runtime.getTokenTracker().recordUsage(inputCharCount, outputCharCount.get());
                            // 注入技能的轮次成败归因（滑窗成功率达标视为成功）
                            recordSkillTurnOutcome(isTurnSuccessful());
                            // 记忆：轮后异步落情景 + 向量去重蒸馏事实（替代旧 distill/consolidate 批处理）
                            memoryService.rememberTurn("chat", userInput, collectedReply.toString(), null);
                            // 技能蒸馏（程序性记忆）：达门槛时从执行轨迹蒸馏可沉淀的工作流经验
                            skillCurator.distillFromChatTurn(userInput, collectedReply.toString(),
                                            executionMonitor.getTraces(), executionMonitor.successRate())
                                    .subscribe();
                            callbacks.onComplete();
                        }
                );
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
    private void rebuildOrchestratorForTurn(RoutingResult routing, GoalDecomposition goals) {
        synchronized (orchestratorLock) {
            // 发送前自愈：修复上一轮取消/中断/超时留下的悬空工具调用，
            // 否则带 tool_calls 却缺结果的历史会被网关以
            // "Pending tool calls exist without results" 整体拒绝。
            runtime.getMemoryManager().healOrchestratorDanglingToolCalls();
            List<String> groups = (routing.isFallback() || !routing.hasToolGroups())
                    ? new ArrayList<>(RoutingResult.ALL_TOOL_GROUPS)
                    : new ArrayList<>(routing.toolGroups());
            // 任何路由场景（含全量降级）都强制保留 ALWAYS_ACTIVE_GROUPS：
            // clarify（主动向用户澄清）、skill（skill_read 按需拉取技能正文），二者均与路由解耦
            for (String g : RoutingResult.ALWAYS_ACTIVE_GROUPS) {
                if (!groups.contains(g)) groups.add(g);
            }
            // 插件工具桥同样每轮常驻（动态工具，路由器不感知）
            if (!groups.contains("plugins")) groups.add("plugins");
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
            // 恢复后自愈悬空工具调用（上次可能停在 tool_call 与结果之间）
            runtime.getMemoryManager().healDanglingToolCalls(mem, "orchestrator");
            log.info("会话已从记忆库检查点恢复: {} ({} 条消息)", sessionId, msgs.size());
        } catch (Exception e) {
            log.warn("恢复会话检查点失败（使用空白状态）: {}", sessionId, e);
        }
    }

    public void deleteSession(String sessionId) {
        memoryService.deleteCheckpoint(sessionId);
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
        cancelStream();
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
