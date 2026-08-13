package com.javaclaw.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.goal.GoalDecomposition;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.loop.agent.FrameworkCompletionJudge;
import com.javaclaw.loop.agent.FrameworkLoopRunner;
import com.javaclaw.loop.model.Cadence;
import com.javaclaw.loop.model.CarryForwardMode;
import com.javaclaw.loop.model.LoopSpec;
import com.javaclaw.loop.model.StopConditions;
import com.javaclaw.prompt.LoopPrompts;
import com.javaclaw.task.sdd.agent.ProcessCommandRunner;
import com.javaclaw.task.sdd.verify.CommandRunner;
import com.javaclaw.util.ProjectAccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 循环模式门面：把一次「循环」请求装配成确定性引擎并驱动运行。
 *
 * <p>职责：解析指令 → 目标分解出成功准则 → 组装 {@link LoopController}（注入真实执行体
 * {@link FrameworkLoopRunner}、可选验收员、命令执行器）→ 在 {@code boundedElastic} 上跑，
 * 联动 {@link ToolConfirmationManager} 放宽托管确认。运行态按会话封装在独立的
 * {@link LoopInvocation} 中，服务本身不保存“当前运行”的单例状态。</p>
 */
public final class LoopService {

    private static final Logger log = LoggerFactory.getLogger(LoopService.class);
    private static final com.javaclaw.workflow.model.GraphDefinition SYSTEM_GRAPH =
            com.javaclaw.workflow.service.SystemGraphFactory.loop();

    private final com.javaclaw.framework.api.AgentClient agents;
    private final com.javaclaw.runtime.WorkspaceContext workspace;
    private final com.javaclaw.framework.spi.ModelTaskGateway modelTasks;
    private final AgentConfig config;
    private final com.javaclaw.workflow.service.WorkflowService workflowService;
    private final com.javaclaw.platform.process.ProcessRunner processes;
    private final ConcurrentHashMap<String, LoopInvocation> invocations = new ConcurrentHashMap<>();
    private final ObjectMapper json;

    /** 重建/关闭路径等待循环线程停稳的上限（毫秒）；超时仅告警，不无限阻塞调用线程。 */
    private static final long TERMINATION_WAIT_MILLIS = 5_000L;

    public LoopService(
            AgentConfig config,
            com.javaclaw.workflow.service.WorkflowService workflowService,
            com.javaclaw.platform.process.ProcessRunner processes,
            com.javaclaw.framework.api.AgentClient agents,
            com.javaclaw.runtime.WorkspaceContext workspace,
            com.javaclaw.framework.spi.ModelTaskGateway modelTasks,
            ObjectMapper json) {
        this.config = java.util.Objects.requireNonNull(config, "config");
        this.workflowService = workflowService;
        this.processes = java.util.Objects.requireNonNull(processes, "processes");
        this.agents = java.util.Objects.requireNonNull(agents, "agents");
        this.workspace = java.util.Objects.requireNonNull(workspace, "workspace");
        this.modelTasks = java.util.Objects.requireNonNull(modelTasks, "modelTasks");
        this.json = java.util.Objects.requireNonNull(json, "json");
        if (workflowService != null) workflowService.systemGraphs().register(SYSTEM_GRAPH);
    }

    /**
     * 启动一次循环（立即返回，异步执行；终态经 {@code callbacks} 通告）。
     */
    public com.javaclaw.api.conversation.ConversationHandle start(
            ConversationRequest request, ConversationCallbacks callbacks) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(callbacks, "callbacks");
        LoopInvocation invocation = new LoopInvocation(request.sessionId());
        com.javaclaw.api.conversation.TerminalCallbackGuard userCallbacks =
                new com.javaclaw.api.conversation.TerminalCallbackGuard(callbacks);
        if (invocations.putIfAbsent(request.sessionId(), invocation) != null) {
            userCallbacks.onTerminal(ConversationOutcome.failed(
                    new IllegalStateException("当前会话已有循环正在运行，请先停止或等待完成")));
            return new com.javaclaw.api.conversation.DefaultConversationHandle(
                    userCallbacks, ignored -> false);
        }

        // 底层终态和句柄取消分开仲裁：句柄可立即反馈已取消，但会话运行槽只在 Workflow/
        // Loop 节点真正退出后释放，防止旧运行收尾期间同会话的新运行穿透。
        com.javaclaw.api.conversation.TerminalCallbackGuard pipelineCallbacks =
                new com.javaclaw.api.conversation.TerminalCallbackGuard(new ConversationCallbacks() {
                    @Override
                    public void onEvent(com.javaclaw.api.conversation.ConversationEvent event) {
                        userCallbacks.onEvent(event);
                    }

                    @Override
                    public void onTerminal(ConversationOutcome outcome) {
                        invocation.finish();
                        invocations.remove(invocation.sessionId, invocation);
                        userCallbacks.onTerminal(outcome);
                    }
                });
        try {
            startLoopPipeline(invocation, request, pipelineCallbacks);
        } catch (Throwable failure) {
            pipelineCallbacks.onTerminal(ConversationOutcome.failed(failure));
        }
        return new com.javaclaw.api.conversation.DefaultConversationHandle(
                userCallbacks, ignored -> cancelInvocation(invocation));
    }

    private void startLoopPipeline(
            LoopInvocation invocation,
            ConversationRequest request,
            ConversationCallbacks callbacks) {
        if (workflowService == null) {
            executeLoopPipeline(invocation, request, callbacks);
            return;
        }
        workflowService.runSystem(SYSTEM_GRAPH, request.sessionId(),
                com.javaclaw.workflow.service.SystemInvocationState.from(request), callbacks,
                (stageId, context) -> executeLoopGraphStage(invocation, stageId, context));
    }

    private void executeLoopPipeline(
            LoopInvocation invocation,
            ConversationRequest request,
            ConversationCallbacks callbacks) {
        executeLoopPipeline(invocation, request, null, false, callbacks);
    }

    private com.javaclaw.workflow.runtime.NodeResult executeLoopGraphStage(
            LoopInvocation invocation,
            String stageId,
            com.javaclaw.workflow.runtime.NodeExecutionContext context) throws Exception {
        ConversationRequest request = com.javaclaw.workflow.service.SystemInvocationState.request(context);
        return switch (stageId) {
            case "preflight" -> {
                Plan plan = buildPlan(request);
                // buildPlan 是阻塞模型调用；期间收到取消后不得继续弹出风险确认框。
                context.cancellation().throwIfCancelled();
                boolean commandsApproved = confirmVerifyCommands(plan.spec());
                // 确认框显示期间也可能取消；此时以 CANCELLED 收束而不是误报“未批准”。
                context.cancellation().throwIfCancelled();
                if (!commandsApproved) {
                    throw new IllegalStateException(
                            "循环验证命令未获批准，已取消启动。可修改目标或允许执行后重试");
                }
                yield com.javaclaw.workflow.runtime.NodeResult.next(
                        com.javaclaw.workflow.model.StatePatch.builder()
                                .setJson("system.loop.spec", json.valueToTree(plan.spec()))
                                .set("system.loop.contextPrompt", plan.contextPrompt())
                                .set("system.loop.explicitWorkDir", plan.explicitWorkDir())
                                .build());
            }
            case "run" -> {
                LoopSpec spec = json.treeToValue(
                        context.state().get("system.loop.spec"), LoopSpec.class);
                Plan plan = new Plan(spec,
                        context.state().get("system.loop.contextPrompt").asText(),
                        context.state().get("system.loop.explicitWorkDir").asBoolean());
                yield com.javaclaw.workflow.service.SystemPipelineAwaiter.await(
                        context,
                        inner -> executeLoopPipeline(invocation, request, plan, true, inner),
                        java.util.Objects.requireNonNull(context.callbacks(), "conversation callbacks"),
                        invocation::cancel);
            }
            default -> throw new IllegalArgumentException("未知循环系统阶段: " + stageId);
        };
    }

    private void executeLoopPipeline(LoopInvocation invocation,
                                     ConversationRequest request, Plan preparedPlan,
                                     boolean verificationConfirmed,
                                     ConversationCallbacks callbacks) {
        if (!invocation.begin()) {
            callbacks.onTerminal(ConversationOutcome.failed(
                    new IllegalStateException("循环 Workflow 节点被重复启动")));
            return;
        }
        Schedulers.boundedElastic().schedule(() -> {
            String loopId = "loop-" + System.nanoTime();
            FrameworkLoopRunner runner = null;
            try {
                Plan plan = preparedPlan == null ? buildPlan(request) : preparedPlan;
                // 启动检查点①：目标分解（阻塞数秒）期间被取消 → 不再弹确认、不启动
                if (invocation.cancelRequested()) {
                    log.info("循环在目标分解阶段被取消，未启动");
                    callbacks.onTerminal(ConversationOutcome.cancelled(
                            com.javaclaw.api.conversation.CancellationReason.USER_REQUEST));
                    return;
                }

                // 验证命令治理：command 类准则由目标分解（LLM）产出、将被验证器反复执行且不经
                // 工具确认闸门——非只读命令必须先经用户确认一次（对齐 SDD 提案评审思想），
                // 拒绝则不启动循环（诚实失败，而非静默跑 LLM 生成的命令）
                if (!verificationConfirmed && !confirmVerifyCommands(plan.spec())) {
                    callbacks.onTerminal(ConversationOutcome.failed(new IllegalStateException(
                            "循环验证命令未获批准，已取消启动。可修改目标或允许执行后重试")));
                    return;
                }
                // 启动检查点②：确认弹窗（最长 60 秒）期间被取消 → 不启动
                if (invocation.cancelRequested()) {
                    log.info("循环在验证命令确认阶段被取消，未启动");
                    callbacks.onTerminal(ConversationOutcome.cancelled(
                            com.javaclaw.api.conversation.CancellationReason.USER_REQUEST));
                    return;
                }

                // 路由文本用目标原文（而非拼装轮次提示）：轮次提示不含目标信息，会导致工具组选错。
                // 来源令牌带 loopId 与显式 workdir=（白名单归属/目录放行基准，见 Plan.explicitWorkDir 注释）
                runner = new FrameworkLoopRunner(
                        agents, workspace, request.sessionId(), loopId, plan.contextPrompt(),
                        plan.explicitWorkDir() ? plan.spec().workDir() : null,
                        config.getLoopIterationTimeoutSeconds());
                invocation.attachRunner(runner);

                // 验证命令超时对齐慢构建场景：默认 120s 会把「盯着 mvn test 直到通过」这类
                // 分钟级命令逐轮误杀，done 永不可达（SDD 路径同理专门调大，见 execTimeoutSec）
                CommandRunner commandRunner = new ProcessCommandRunner(
                        processes, config.getLoopVerifyTimeoutSeconds());
                var judge = plan.spec().useJudge()
                        ? new FrameworkCompletionJudge(modelTasks, runner::lastRunId,
                                runner::cancelled, plan.spec().workDir())
                        : CompletionJudge.CONSERVATIVE_DENY;

                LoopController controller = LoopController.create(
                        plan.spec(), runner, commandRunner, judge, Clock.systemUTC());
                invocation.attachController(controller);

                // 循环半无人值守：确认待遇（放宽超时/白名单/目录放行）由 runner 构造时绑定的
                // 来源令牌承载，无需再登记全局场景；此处只负责循环结束时清掉「同意全部」授权
                try {
                    log.info("循环启动：cadence={} maxIters={} 准则数={}",
                            plan.spec().cadence().mode(), plan.spec().stopConditions().maxIterations(),
                            plan.spec().criteria().size());
                    controller.run(callbacks);
                } finally {
                    ToolConfirmationManager.clearTaskAllowlist(loopId);
                }
            } catch (Exception e) {
                log.error("循环执行异常", e);
                callbacks.onTerminal(ConversationOutcome.failed(e));
            } finally {
                if (runner != null) {
                    runner.shutdown();
                }
                invocation.finish();
            }
        });
    }

    /**
     * 取消当前活跃循环；无活跃循环返回 false。
     *
     * <p>两步缺一不可：① controller.cancel() 置取消标志并唤醒轮间等待；
     * ② runner.shutdown() dispose 进行中的轮的流式订阅——否则在途轮会继续
     * 烧 token 直到单轮超时（默认 12 分钟）才回到取消检查点。</p>
     */
    public boolean cancelActive() {
        boolean accepted = false;
        for (LoopInvocation invocation : invocations.values()) {
            accepted |= cancelInvocation(invocation);
        }
        return accepted;
    }

    /** 只取消指定会话对应的系统图运行；由本轮句柄捕获调用。 */
    public boolean cancelActive(String sessionId) {
        LoopInvocation invocation = invocations.get(sessionId);
        return invocation != null && cancelInvocation(invocation);
    }

    private boolean cancelInvocation(LoopInvocation invocation) {
        boolean graphCancelled = workflowService != null
                && workflowService.cancelSystem(SYSTEM_GRAPH.id(), invocation.sessionId);
        return invocation.cancel() || graphCancelled;
    }

    /**
     * 等待活跃循环线程真正退出；无活跃循环立即返回 true。
     *
     * @return 是否已停稳（false = 超时或被中断，循环线程可能仍在收尾）
     */
    public boolean awaitTermination(long timeoutMillis) {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        for (LoopInvocation invocation : List.copyOf(invocations.values())) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !invocation.await(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                return false;
            }
        }
        return true;
    }

    /** 工作区切换：停掉当前循环并等待停稳（随后共享基础设施会被关闭，不能带着在途轮切换）。 */
    public void reload() {
        cancelAndAwait(com.javaclaw.api.conversation.CancellationReason.RUNTIME_REBUILD);
    }

    /** 应用退出/服务重建：停掉当前循环并等待停稳。 */
    public void shutdown() {
        cancelAndAwait(com.javaclaw.api.conversation.CancellationReason.SHUTDOWN);
    }

    /**
     * 取消 + 等待停稳：单纯发取消信号是「发完即忘」，调用方紧接着关闭共享
     * HttpTransport/MCP/知识库时会与在途循环轮实际并发（验收员调用踩已关资源、
     * 旧循环的 clearTaskAllowlist 与新循环的「同意全部」授权交错弄乱确认白名单）。
     */
    private void cancelAndAwait(com.javaclaw.api.conversation.CancellationReason reason) {
        if (!cancelActive()) {
            return;
        }
        if (!awaitTermination(TERMINATION_WAIT_MILLIS)) {
            log.warn("循环未能在 {}ms 内停稳，随后关闭共享资源可能令在途轮报错（已尽力取消）",
                    TERMINATION_WAIT_MILLIS);
        }
    }

    // ==================== 装配 ====================

    /**
     * 从请求构建执行计划：解析指令 → 分解目标 → 套用配置默认 → 拼装 spec 与上下文提示词。
     */
    private Plan buildPlan(ConversationRequest request) {
        AgentConfig cfg = config;
        LoopDirectives directives = LoopDirectives.parse(request.userInput());
        // 空目标兜底：带着空目标启动只会白烧满上限轮数的模型调用，诚实失败
        if (directives.goal().isBlank()) {
            throw new IllegalArgumentException(
                    "循环目标为空：请在 @loop 指令行后写明目标（或直接把目标写在指令行参数之后）");
        }
        // 循环模式不支持附件（UI 已按 Capabilities 拦截；此处防御其它调用方静默丢附件）
        if (!request.attachments().isEmpty()) {
            log.warn("循环模式不处理附件，已忽略 {} 个附件", request.attachments().size());
        }
        // Goal decomposition is a framework capability (gepa.goal), not a service-owned model
        // runtime. The workflow starts with a deterministic goal shell; the agent profile enriches
        // and evaluates it through the common Run/ModelTask paths.
        GoalDecomposition goal = new GoalDecomposition(
                directives.goal(), List.of(directives.goal()), "完成用户目标", List.of());

        Cadence cadence;
        if (directives.intervalSeconds() > 0) {
            cadence = Cadence.interval(directives.intervalSeconds());
        } else if (directives.intervalSeconds() == 0) {
            // interval=0（用户意图「不等待」）按自驱节奏处理：INTERVAL 节奏整体豁免停滞计数，
            // 零延迟 + 豁免 = 卡住的目标全速连发模型调用烧到轮数/墙钟上限才停；
            // 自驱同样零轮间延迟，但保留无进展护栏（卡住两轮即停）
            log.warn("@loop interval=0 已按自驱节奏处理（零轮间延迟，保留无进展护栏）；"
                    + "定时轮询请给正间隔，如 interval=5m");
            cadence = Cadence.selfPaced();
        } else if (directives.intervalSeconds() == LoopDirectives.INTERVAL_INVALID) {
            // interval 写了但解析失败（如 interval=五分钟）：用户意图明确是定时轮询，
            // 静默降级为零延迟自驱会不间断连发模型调用烧满上限——退配置默认间隔
            long fallback = cfg.getLoopIntervalDelaySeconds();
            log.warn("@loop interval 值无法解析，退回配置默认间隔 {}s（支持 s/m/min/h 后缀，如 interval=5m）",
                    fallback);
            cadence = Cadence.interval(fallback);
        } else {
            cadence = Cadence.selfPaced();
        }
        int maxIterations = directives.maxIterations() > 0
                ? directives.maxIterations()
                : cfg.getLoopMaxIterations();
        if (directives.maxIterations() == LoopDirectives.MAX_INVALID) {
            // max 写了但值非法（如 max=3x / max=0）：预算键静默退默认等于多烧数倍 token，
            // 与 interval 同样明示降级而非无声吞掉
            log.warn("@loop max 值无法解析或非正数，退回配置默认 {} 轮（示例 max=10）", maxIterations);
        }
        boolean useJudge = directives.judge() != null ? directives.judge() : cfg.isLoopJudgeEnabled();
        // 无法本地核验的准则数——口径取「非本地可核验类型」而非枚举 freeform/external_check：
        // LLM 可能产出五类之外的类型码（如 file_exists），这类准则在 CriterionVerifier 同样只能
        // 交验收员，漏计会让核验腿缺位却不触发自动启用。谓词直接复用 CriterionVerifier 的
        // 单一来源（其 verify 分派同口径），供下方自动启用与告警共用
        long unverifiable = goal.getCriteria().stream()
                .filter(c -> !CriterionVerifier.isLocallyVerifiable(c.normalizedType()))
                .count();
        // 「提议 ∧ 核验」的核验腿缺位有两种形态，都必须兜底：① 分解不出任何准则——judge 关闭
        // 的话完成判定完全取决于执行体自报 done，第一轮谎报即假完成收工；② 分解出的准则含
        // freeform/external_check——judge 关闭时这些准则由 CONSERVATIVE_DENY 永远驳回，done
        // 不可达，循环注定烧到轮数/墙钟上限以失败收场。两者都与「绝不默认放行」的承重墙矛盾。
        // 用户未显式表态 judge 时自动启用验收员（代价：每次完成提议多一次模型调用）；
        // 显式 @loop judge=off 视为知情选择，保留原语义、由下方告警明示风险
        if ((goal.getCriteria().isEmpty() || unverifiable > 0) && directives.judge() == null && !useJudge) {
            useJudge = true;
            log.warn("循环目标{}：已自动启用模型验收员兜底完成判定"
                    + "（执行体自报完成须经验收员核验才算数；如确要仅凭自报，可显式 @loop judge=off）",
                    goal.getCriteria().isEmpty()
                            ? "未分解出客观成功准则"
                            : "含 " + unverifiable + " 条无法本地核验的准则（freeform/external_check/未知类型）");
        }

        StopConditions conditions = new StopConditions(
                maxIterations, cfg.getLoopTokenBudget(), cfg.getLoopMaxWallClockSeconds());

        // 工作目录：@loop workdir= 显式指定优先；未指定退回应用目录（准则核验的路径/命令基准）
        String workDir;
        try {
            workDir = directives.workDir() != null
                    ? ProjectAccessPolicy.resolveProjectPath(directives.workDir()).toString()
                    : ProjectAccessPolicy.projectRoot().toString();
        } catch (SecurityException e) {
            throw new IllegalArgumentException("@loop workdir 必须位于当前项目目录内", e);
        }
        // 显式指定的目录必须存在：ProcessCommandRunner 对不存在目录会静默回退进程 cwd，
        // 验证命令将在 JavaClaw 自身目录里跑出假结果（假完成或永不满足），诚实失败优于静默错位
        if (directives.workDir() != null && !new java.io.File(workDir).isDirectory()) {
            throw new IllegalArgumentException("@loop workdir 指定的目录不存在：" + workDir
                    + "（注意：指令按空白切分，暂不支持含空格的路径）");
        }

        LoopSpec spec = new LoopSpec(
                directives.goal(),
                workDir,
                goal.getCriteria(),
                cadence,
                conditions,
                // SUMMARY 接力：历轮简述（loop_report 的 summary，零额外成本）+ 末轮全文，
                // 上下文有界增长又不丢历史脉络
                CarryForwardMode.SUMMARY,
                useJudge);

        // 完成判定的可信度告警：明示而非静默降级
        if (spec.criteria().isEmpty() && !spec.useJudge()) {
            // 只剩显式 judge=off 才会走到这里（未表态者已被上方自动启用验收员）：
            // 自报即完成、无任何核验，明示风险由用户自担
            log.warn("循环无客观成功准则且已显式关闭验收员（judge=off）：完成判定将完全依赖"
                    + "执行体自报，自报 done 当轮即完成、无任何核验；「无进展」检测对自由文本也很弱。"
                    + "建议去掉 judge=off 或把目标写得可核验（命令/文件/关键词）");
        } else if (!spec.useJudge() && unverifiable > 0) {
            // 静默死局明示（只剩显式 judge=off 可达此分支——未表态者已被上方自动启用验收员）：
            // freeform/external_check 准则永远不通过 → done 不可达 → 循环必然烧到上限才停
            log.warn("循环有 {} 条准则（freeform/external_check/未知类型）无法本地核验且已显式关闭验收员，"
                    + "这些准则永远不会通过、循环无法判定完成，只能烧到轮数/时长上限。"
                    + "强烈建议去掉 judge=off", unverifiable);
        }

        // 目标原文必须始终注入系统提示词——buildContextPrompt() 在分解失败/跳过时返回空串，
        // 不能作为目标的唯一注入通道（曾导致执行体收到一套循环纪律却不知目标为何的事故）
        String contextPrompt = LoopPrompts.EXECUTION_SYS_PROMPT
                + LoopPrompts.buildGoalSection(directives.goal())
                + goal.buildContextPrompt();
        return new Plan(spec, contextPrompt, directives.workDir() != null);
    }

    /**
     * 非只读验证命令的一次性用户确认。
     *
     * <p>command 类准则的命令文本由目标分解模型生成，验证器将绕过工具确认反复执行——
     * 只读命令（查询类，零副作用）自动放行；其余命令整批向用户确认一次，拒绝即不启动。</p>
     *
     * @return 是否放行（无需确认或用户同意）
     */
    private boolean confirmVerifyCommands(LoopSpec spec) {
        // 用归一化类型过滤（与 CriterionVerifier 的分派同源）：LLM 分解产出的类型码可能带首尾
        // 空白，原始 equals 过滤不命中会跳过确认、而核验器 trim 后仍逐轮真实执行该命令——
        // 确认闸门与执行必须用同一份类型判定，否则治理被静默绕过
        List<String> risky = spec.criteria().stream()
                .filter(c -> com.javaclaw.loop.LoopConstants.CRITERION_COMMAND_EXIT_ZERO
                        .equals(c.normalizedType()))
                .map(c -> c.predicate)
                .filter(cmd -> cmd != null && !cmd.isBlank())
                .filter(cmd -> !com.javaclaw.agent.risk.ReadOnlyCommands.isReadOnly(cmd))
                .toList();
        if (risky.isEmpty()) {
            return true;
        }
        // 独立确认（UNKNOWN 来源）：不吃任何任务级「同意全部」白名单——循环尚未启动、
        // 令牌尚未装配，命中并发任务的授权等于本闸门被静默绕过。该入口对 AUTO 总闸
        // 也保留人工底线：这些命令由 LLM 生成且将绕过工具确认逐轮真实执行，与 cmd_execute
        // 的高风险命令同一待遇（全自动审核不应把它们纳入静默放行）
        boolean approved = ToolConfirmationManager.requestStandaloneConfirmation("loop_verify",
                "循环验证将反复执行以下命令（每次核验成功准则时）：\n"
                        + String.join("\n", risky)
                        + "\n工作目录：" + spec.workDir());
        if (!approved) {
            log.warn("循环验证命令未获用户批准，取消启动：{}", risky);
        }
        return approved;
    }

    /** 内部执行计划：规格 + 拼给执行体的上下文提示词。 */
    /**
     * @param explicitWorkDir 工作目录是否由用户经 {@code @loop workdir=} 显式指定。
     *                        只有显式目录才作为托管场景风险评估的「影响范围」放行基准——
     *                        默认的 user.dir 可能是用户主目录，把它交给免人工自动放行
     *                        等于把整个主目录纳入无确认写删范围（准则核验仍照常用默认目录）
     */
    private record Plan(LoopSpec spec, String contextPrompt, boolean explicitWorkDir) {}

    /** Mutable state owned by one Workflow/Loop run, never by the singleton service. */
    private static final class LoopInvocation {
        private final String sessionId;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<LoopController> controller = new AtomicReference<>();
        private final AtomicReference<FrameworkLoopRunner> runner = new AtomicReference<>();
        private final java.util.concurrent.CountDownLatch stopped =
                new java.util.concurrent.CountDownLatch(1);

        private LoopInvocation(String sessionId) {
            this.sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
        }

        private boolean begin() {
            return started.compareAndSet(false, true);
        }

        private boolean cancelRequested() {
            return cancelled.get();
        }

        private void attachController(LoopController value) {
            controller.set(value);
            if (cancelled.get()) value.cancel();
        }

        private void attachRunner(FrameworkLoopRunner value) {
            runner.set(value);
            if (cancelled.get()) value.shutdown();
        }

        private boolean cancel() {
            if (finished.get()) return false;
            boolean accepted = cancelled.compareAndSet(false, true);
            LoopController currentController = controller.get();
            if (currentController != null) currentController.cancel();
            FrameworkLoopRunner currentRunner = runner.get();
            if (currentRunner != null) currentRunner.shutdown();
            return accepted;
        }

        private void finish() {
            if (finished.compareAndSet(false, true)) {
                controller.set(null);
                runner.set(null);
                stopped.countDown();
            }
        }

        private boolean await(long timeout, java.util.concurrent.TimeUnit unit) {
            try {
                return stopped.await(timeout, unit);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
