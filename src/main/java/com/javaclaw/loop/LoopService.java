package com.javaclaw.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.goal.GoalDecomposition;
import com.javaclaw.agent.goal.GoalManager;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationRequest;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.loop.agent.AgentScopeCompletionJudge;
import com.javaclaw.loop.agent.AgentScopeLoopRunner;
import com.javaclaw.loop.model.Cadence;
import com.javaclaw.loop.model.CarryForwardMode;
import com.javaclaw.loop.model.LoopSpec;
import com.javaclaw.loop.model.StopConditions;
import com.javaclaw.prompt.LoopPrompts;
import com.javaclaw.task.sdd.agent.ProcessCommandRunner;
import com.javaclaw.task.sdd.verify.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 循环模式门面：把一次「循环」请求装配成确定性引擎并驱动运行。
 *
 * <p>职责：解析指令 → 目标分解出成功准则 → 组装 {@link LoopController}（注入真实执行体
 * {@link AgentScopeLoopRunner}、可选验收员、命令执行器）→ 在 {@code boundedElastic} 上跑，
 * 联动 {@link ToolConfirmationManager} 放宽托管确认。同一时刻只允许一个活跃循环。</p>
 */
public final class LoopService {

    private static final Logger log = LoggerFactory.getLogger(LoopService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final com.javaclaw.workflow.model.GraphDefinition SYSTEM_GRAPH =
            com.javaclaw.workflow.service.SystemGraphFactory.loop();

    private final AgentRuntime runtime;
    private final com.javaclaw.workflow.service.WorkflowService workflowService;
    private volatile String activeSystemSessionId;
    private final GoalManager goalManager;

    /** 当前活跃循环控制器（供取消）；无活跃循环为 null。 */
    private final AtomicReference<LoopController> active = new AtomicReference<>();
    /** 当前活跃循环的执行体（取消时需 dispose 进行中的轮，否则在途轮会烧到单轮超时）。 */
    private final AtomicReference<AgentScopeLoopRunner> activeRunner = new AtomicReference<>();
    /** 单活跃循环闸：CAS 抢占，避免并发启动多个循环。 */
    private final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * 生命周期锁：串行化「启动抢位」与「取消/等待停稳」的状态迁移。start() 在锁内一并完成
     * {@code running 抢占 + finished 换本代次新闩 + cancelRequested 复位} 三步，cancelActive()
     * 与 awaitTermination() 也在锁内读写这些字段——否则 running 已置真而 finished 尚指向上一
     * 代次已归零的闩（并发关闭误判已停稳、在运行中的循环下关共享资源），或取消请求落在 CAS
     * 与复位之间被静默清掉（两处 CONFIRMED 竞争）。
     */
    private final Object lifecycleLock = new Object();
    /**
     * 启动窗口取消标志：{@code active}/{@code activeRunner} 在目标分解（阻塞模型调用）与
     * 验证命令确认弹窗（最长 60 秒）之后才 set，这段窗口里 {@link #cancelActive()} 找不到
     * 可取消对象——用户此时按停止会空转，循环随后照常启动且事件全被旧代次丢弃（不可见地
     * 烧满预算）。置位后启动流程在各检查点主动让位。
     */
    private volatile boolean cancelRequested;
    /** 活跃循环的停稳闩：run 线程退出（finally）时放行，供服务重建路径等待真正停稳。 */
    private volatile java.util.concurrent.CountDownLatch finished;

    /** 目标分解缓存 TTL（毫秒），与 GoalManager 聊天模式默认一致。 */
    private static final long GOAL_CACHE_TTL_MILLIS = 600_000L;
    /** 重建/关闭路径等待循环线程停稳的上限（毫秒）；超时仅告警，不无限阻塞调用线程。 */
    private static final long TERMINATION_WAIT_MILLIS = 5_000L;

    public LoopService(AgentRuntime runtime) {
        this(runtime, null);
    }

    public LoopService(AgentRuntime runtime, com.javaclaw.workflow.service.WorkflowService workflowService) {
        this.runtime = runtime;
        this.workflowService = workflowService;
        if (workflowService != null) workflowService.systemGraphs().register(SYSTEM_GRAPH);
        // skipLength=0：聊天模式的「短请求免分解」优化不适用于循环——循环目标多为短祈使句，
        // 而成功准则是循环完成判定的承重墙（不是可有可无的优化），必须对任意长度目标分解。
        this.goalManager = new GoalManager(runtime.getModelFactory().createChatModel(),
                runtime.getTokenTracker(), 0, GOAL_CACHE_TTL_MILLIS);
    }

    /**
     * 启动一次循环（立即返回，异步执行；终态经 {@code callbacks} 通告）。
     */
    public void start(ConversationRequest request, ConversationCallbacks callbacks) {
        if (workflowService == null) {
            executeLoopPipeline(request, callbacks);
            return;
        }
        activeSystemSessionId = request.sessionId();
        workflowService.runSystem(SYSTEM_GRAPH, request.sessionId(),
                com.javaclaw.workflow.service.SystemInvocationState.from(request), callbacks,
                this::executeLoopGraphStage);
    }

    private void executeLoopPipeline(ConversationRequest request, ConversationCallbacks callbacks) {
        executeLoopPipeline(request, null, false, callbacks);
    }

    private com.javaclaw.workflow.runtime.NodeResult executeLoopGraphStage(
            String stageId, com.javaclaw.workflow.runtime.NodeExecutionContext context) throws Exception {
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
                                .set("system.loop.spec", plan.spec())
                                .set("system.loop.contextPrompt", plan.contextPrompt())
                                .set("system.loop.explicitWorkDir", plan.explicitWorkDir())
                                .build());
            }
            case "run" -> {
                LoopSpec spec = MAPPER.treeToValue(
                        context.state().get("system.loop.spec"), LoopSpec.class);
                Plan plan = new Plan(spec,
                        context.state().get("system.loop.contextPrompt").asText(),
                        context.state().get("system.loop.explicitWorkDir").asBoolean());
                yield com.javaclaw.workflow.service.SystemPipelineAwaiter.await(
                        context,
                        inner -> executeLoopPipeline(request, plan, true, inner),
                        context.require(ConversationCallbacks.class),
                        this::cancelPipeline);
            }
            default -> throw new IllegalArgumentException("未知循环系统阶段: " + stageId);
        };
    }

    private void executeLoopPipeline(ConversationRequest request, Plan preparedPlan,
                                     boolean verificationConfirmed,
                                     ConversationCallbacks callbacks) {
        java.util.concurrent.CountDownLatch latch;
        synchronized (lifecycleLock) {
            if (!running.compareAndSet(false, true)) {
                callbacks.onError(new IllegalStateException("已有循环正在运行，请先停止当前循环"));
                return;
            }
            // 三步锁内原子完成：抢占运行位 + 装本代次停稳闩 + 复位取消标志（见 lifecycleLock 注释）
            latch = new java.util.concurrent.CountDownLatch(1);
            finished = latch;
            cancelRequested = false;
        }
        final java.util.concurrent.CountDownLatch finishedLatch = latch;
        Schedulers.boundedElastic().schedule(() -> {
            String loopId = "loop-" + System.nanoTime();
            AgentScopeLoopRunner runner = null;
            try {
                Plan plan = preparedPlan == null ? buildPlan(request) : preparedPlan;
                // 启动检查点①：目标分解（阻塞数秒）期间被取消 → 不再弹确认、不启动
                if (cancelRequested) {
                    log.info("循环在目标分解阶段被取消，未启动");
                    callbacks.onComplete();
                    return;
                }

                // 验证命令治理：command 类准则由目标分解（LLM）产出、将被验证器反复执行且不经
                // 工具确认闸门——非只读命令必须先经用户确认一次（对齐 SDD 提案评审思想），
                // 拒绝则不启动循环（诚实失败，而非静默跑 LLM 生成的命令）
                if (!verificationConfirmed && !confirmVerifyCommands(plan.spec())) {
                    callbacks.onError(new IllegalStateException(
                            "循环验证命令未获批准，已取消启动。可修改目标或允许执行后重试"));
                    return;
                }
                // 启动检查点②：确认弹窗（最长 60 秒）期间被取消 → 不启动
                if (cancelRequested) {
                    log.info("循环在验证命令确认阶段被取消，未启动");
                    callbacks.onComplete();
                    return;
                }

                // 路由文本用目标原文（而非拼装轮次提示）：轮次提示不含目标信息，会导致工具组选错。
                // 来源令牌带 loopId 与显式 workdir=（白名单归属/目录放行基准，见 Plan.explicitWorkDir 注释）
                runner = new AgentScopeLoopRunner(runtime, plan.contextPrompt(), plan.spec().goalPrompt(),
                        com.javaclaw.agent.ToolCallOrigin.managedTask(loopId,
                                plan.explicitWorkDir() ? plan.spec().workDir() : null));
                activeRunner.set(runner);

                // 验证命令超时对齐慢构建场景：默认 120s 会把「盯着 mvn test 直到通过」这类
                // 分钟级命令逐轮误杀，done 永不可达（SDD 路径同理专门调大，见 execTimeoutSec）
                CommandRunner commandRunner = new ProcessCommandRunner(
                        AgentConfig.getInstance().getLoopVerifyTimeoutSeconds());
                var judge = plan.spec().useJudge()
                        ? new AgentScopeCompletionJudge(plan.spec().workDir(), runtime.getModelFactory())
                        : CompletionJudge.CONSERVATIVE_DENY;

                LoopController controller = LoopController.create(
                        plan.spec(), runner, commandRunner, judge, Clock.systemUTC());
                active.set(controller);
                // 启动检查点③：cancelActive 可能恰在 active.set 之前读到 null 而漏掉
                // controller.cancel()——此处补一刀，run() 的轮前护栏会立即以 CANCELLED 收束
                if (cancelRequested) {
                    controller.cancel();
                }

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
                callbacks.onError(e);
            } finally {
                if (runner != null) {
                    runner.shutdown();
                    // 同步关浏览器兜底：本 finally 跑在循环 run 线程（boundedElastic）上，
                    // 停稳闩在其后放行——应用退出路径 cancelAndAwait 等到闩放行时浏览器
                    // 已优雅落地；shutdown() 里的异步关闭在 JVM 退出前可能来不及执行
                    runner.closeBrowserSync();
                }
                activeRunner.set(null);
                active.set(null);
                running.set(false);
                finishedLatch.countDown(); // 最后放行停稳闩：重建路径以此确认循环线程已退出
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
        boolean graphCancelled = workflowService != null
                && workflowService.cancelSystem(SYSTEM_GRAPH.id(), activeSystemSessionId);
        return cancelPipeline() || graphCancelled;
    }

    private boolean cancelPipeline() {
        LoopController controller;
        AgentScopeLoopRunner runner;
        // 锁内置标志 + 读快照：与 start() 的三步锁段互斥，取消请求绝不会落在 CAS 与复位之间被清掉。
        // 先置启动窗口标志再取消具体对象：controller/runner 可能尚未 set（目标分解、确认弹窗阶段），
        // 标志保证启动流程在检查点让位，不会「取消空转、循环照跑」
        synchronized (lifecycleLock) {
            if (!running.get()) {
                return false;
            }
            cancelRequested = true;
            controller = active.get();
            runner = activeRunner.get();
        }
        // cancel()/shutdown() 放到锁外执行：仅置标志/唤醒/dispose 订阅，不必占着生命周期锁
        if (controller != null) {
            controller.cancel();
        }
        if (runner != null) {
            runner.shutdown();
        }
        return true;
    }

    /**
     * 等待活跃循环线程真正退出；无活跃循环立即返回 true。
     *
     * @return 是否已停稳（false = 超时或被中断，循环线程可能仍在收尾）
     */
    public boolean awaitTermination(long timeoutMillis) {
        // 锁内取本代次闩：与 start() 的换闩锁段互斥，绝不会读到上一代次已归零的旧闩而误判已停稳
        java.util.concurrent.CountDownLatch latch;
        synchronized (lifecycleLock) {
            latch = finished;
        }
        if (latch == null) {
            return true;
        }
        try {
            return latch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 工作区切换：停掉当前循环并等待停稳（随后共享基础设施会被关闭，不能带着在途轮切换）。 */
    public void reload() {
        cancelAndAwait();
    }

    /** 应用退出/服务重建：停掉当前循环并等待停稳。 */
    public void shutdown() {
        cancelAndAwait();
    }

    /**
     * 取消 + 等待停稳：单纯发取消信号是「发完即忘」，调用方紧接着关闭共享
     * HttpTransport/MCP/知识库时会与在途循环轮实际并发（验收员调用踩已关资源、
     * 旧循环的 clearTaskAllowlist 与新循环的「同意全部」授权交错弄乱确认白名单）。
     */
    private void cancelAndAwait() {
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
        AgentConfig cfg = AgentConfig.getInstance();
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
        GoalDecomposition goal = goalManager.decompose(directives.goal());

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
        String workDir = directives.workDir() != null
                ? directives.workDir()
                : System.getProperty("user.dir");
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
}
