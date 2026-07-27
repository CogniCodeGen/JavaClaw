package com.javaclaw.loop.agent;

import com.javaclaw.agent.AgentRuntime;
import com.javaclaw.agent.ToolkitAssembler;
import com.javaclaw.agent.expert.ExpertManager;
import com.javaclaw.agent.handler.StreamEventHandler;
import com.javaclaw.agent.router.RoutingResult;
import com.javaclaw.agent.router.ToolRouter;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.api.conversation.ConversationEvent;
import com.javaclaw.api.conversation.ConversationOutcome;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.loop.LoopConstants;
import com.javaclaw.loop.LoopIterationRunner;
import com.javaclaw.loop.model.IterationResult;
import com.javaclaw.prompt.AgentPrompts;
import com.javaclaw.util.AtomicDisposable;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link LoopIterationRunner} 的 AgentScope 实现：单轮循环的真实执行体。
 *
 * <p>沿用 {@code ScheduledTaskAgent} 的隔离范式——独立 {@link ExpertManager} + <b>每轮</b>独立
 * {@link Toolkit}（防超时轮僵尸汇报污染，见 {@link #reportTool}）+ 每轮独立
 * {@link AutoContextMemory} + 独立订阅，使循环可与交互聊天真正并行、互不 dispose 订阅。与定时任务的差别有二：①系统提示词额外拼入循环执行体
 * 提示词与目标上下文；②本轮执行体的最终回复被截获（累加 {@link ConversationEvent.Reply}
 * 片段），连同用量折成 {@link IterationResult} 返回，供确定性引擎做完成/继续/停止判定。</p>
 */
public final class AgentScopeLoopRunner implements LoopIterationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeLoopRunner.class);

    /** 轮次汇报工具所在的常驻工具组名（不参与路由裁剪，每轮必须可用）。 */
    private static final String LOOP_TOOL_GROUP = "loop";

    private final AgentRuntime runtime;
    private final ExpertManager expertManager;
    /**
     * 本循环专属的隔离浏览器：循环设计为可与交互聊天<b>并行</b>，若共用 {@code runtime} 的单个
     * 浏览器实例，两条路径的导航/点击会交错落到错误页面、互相冲掉激活 Tab 与会话。故给循环一个
     * 独立实例（懒启动——循环不用浏览器则永不拉起 Chromium），启动时继承共享登录态、退出时不回写
     * （{@code persistCookies=false}），由 {@link #shutdown()} 关闭。
     */
    private final com.javaclaw.browser.PlaywrightBrowserManager loopBrowser;
    private final StreamEventHandler eventHandler = new StreamEventHandler();
    private final ToolRouter toolRouter;
    private final String baseSystemPrompt;
    private final String routingText;
    private final StreamOptions streamOptions;
    private final long iterationTimeoutSeconds;
    private final AtomicDisposable subscription = new AtomicDisposable();
    /**
     * 终止标志：{@link #shutdown()} 可能落在 route()（阻塞模型调用）与 subscribe 之间的窗口，
     * 此时 subscription 里还没有可 dispose 的订阅——单靠 dispose 会漏杀刚启动的流，
     * 让被取消的循环继续执行工具直到单轮超时。置位后 runOnce 在订阅前后各查一次，兜住窗口。
     */
    private volatile boolean closed;

    /** 路由结果缓存：目标在整个循环内不变，路由一次后各轮复用（省每轮一次轻量模型调用）。 */
    private volatile RoutingResult cachedRouting;

    /**
     * 当前轮的汇报工具：执行体每轮经它提交结构化汇报（模型写入 → consume）。
     *
     * <p><b>一轮一实例、一轮一 toolkit</b>：布尔闸门只能挡住「close() 之后、下一轮 reset()
     * 之前」抵达的迟到写入；且 AgentScope 在<b>分发时按名字</b>从 toolkit 解析工具——若跨轮
     * 共享 toolkit、仅原位换绑实例，被 dispose 的僵尸执行体在换绑后才分发的 loop_report
     * 仍会命中新实例，陈旧汇报被误当成新一轮的汇报消费（假完成提议 / 幻影等待轮）。
     * 整个 toolkit 每轮独立后，僵尸执行体持有的旧 toolkit 只能解析到旧实例（已永久关闸），
     * 陈旧汇报必被丢弃。本字段始终指向当前轮实例，供失败出口与 {@link #shutdown()} 关闸。</p>
     */
    private volatile LoopReportTool reportTool = new LoopReportTool();

    /** 本循环的调用来源令牌（managedTask(loopId, workDir)），注入到每轮 toolkit 的全部带确认工具。 */
    private final com.javaclaw.agent.ToolCallOrigin origin;

    /**
     * @param runtime           基础设施容器
     * @param loopContextPrompt 循环执行体提示词 + 目标/成功准则上下文（拼在编排器系统提示词之后）
     * @param routingText       工具路由依据文本（应传目标原文——轮次提示不含目标信息，用它路由会选错工具组）
     * @param origin            本循环的调用来源令牌（决定确认路径的白名单归属/目录放行基准/超时档位）
     */
    public AgentScopeLoopRunner(AgentRuntime runtime, String loopContextPrompt, String routingText,
                                com.javaclaw.agent.ToolCallOrigin origin) {
        this.runtime = runtime;
        this.routingText = routingText == null ? "" : routingText;
        this.origin = origin == null ? com.javaclaw.agent.ToolCallOrigin.UNKNOWN : origin;
        AgentConfig config = AgentConfig.getInstance();
        // 隔离浏览器：独立于 runtime 的交互浏览器，避免与并行聊天抢同一浏览器/Tab（见字段注释）。
        // 浏览器状态目录用工作区浏览器目录下的 loop 子目录；会话状态启动时读共享 H2 行（继承登录）、
        // 退出时不回写（persistCookies=false），防止临时会话覆盖交互浏览器仍在用的登录态。
        this.loopBrowser = new com.javaclaw.browser.PlaywrightBrowserManager(true,
                com.javaclaw.config.WorkspaceManager.getInstance().getCurrentBrowserDir().resolve("loop"),
                com.javaclaw.config.DataManager.getInstance().getScreenshotsDir(),
                false);
        this.expertManager = new ExpertManager(
                runtime.getModelFactory(), loopBrowser, this.origin);
        this.toolRouter = config.isToolRoutingEnabled()
                ? new ToolRouter(runtime.getModelFactory().createLightChatModel(), runtime.getTokenTracker())
                : null;
        this.baseSystemPrompt = AgentPrompts.ORCHESTRATOR_SYS_PROMPT
                + "\n\n" + (loopContextPrompt == null ? "" : loopContextPrompt);
        this.iterationTimeoutSeconds = config.getLoopIterationTimeoutSeconds();
        this.streamOptions = ToolkitAssembler.buildStreamOptions(config);
    }

    /** 构建本轮独立工具集：标准装配（单一来源见 {@link ToolkitAssembler}，半无人值守路径不含
     * 会弹桌面窗口的媒体工具）+ 循环专属的汇报工具组。纯内存装配（专家/浏览器等重资源由
     * runtime/expertManager 跨轮持有），逐轮重建成本可忽略；每轮独立 toolkit 是防僵尸汇报
     * 污染的关键（见 {@link #reportTool} 注释）。 */
    private Toolkit buildToolkit(LoopReportTool roundReportTool) {
        Toolkit tk = ToolkitAssembler.buildBaseToolkit(runtime, expertManager, false, origin);
        // 轮次汇报工具：循环协议的核心通道，独立成组且必须常驻激活（不参与路由裁剪）
        tk.createToolGroup(LOOP_TOOL_GROUP, LOOP_TOOL_GROUP, true);
        tk.registration().tool(roundReportTool).group(LOOP_TOOL_GROUP).apply();
        return tk;
    }

    @Override
    public IterationResult runOnce(String prompt, ConversationCallbacks outer) {
        // 用量计数器声明在 try 外：中断/异常出口与其它失败出口同口径保留已烧用量，
        // 否则被中断的昂贵轮（可能已数万 token）不计入 TOKEN_BUDGET 护栏与状态卡。
        // 用 AtomicLong：超时出口读值时 latch 未放行、与事件线程的写无 happens-before，
        // 普通 long 读到陈旧值会让失败轮的用量被低估
        java.util.concurrent.atomic.AtomicLong inTokens = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong outTokens = new java.util.concurrent.atomic.AtomicLong();
        try {
            if (closed) {
                return IterationResult.failed();
            }
            RoutingResult routing = route();
            // 每轮独立 toolkit + 全新汇报工具实例（防僵尸汇报污染，见 reportTool 字段注释）
            LoopReportTool freshTool = new LoopReportTool();
            Toolkit roundToolkit = buildToolkit(freshTool);
            reportTool = freshTool; // 失败出口/shutdown 经此字段关当前轮的闸
            // 激活组 + 拼提示词的每轮装配走单一来源（与定时任务路径共用），循环额外常驻汇报组
            String routedPrompt = ToolkitAssembler.activateRoutedGroups(
                    roundToolkit, routing, runtime, LOOP_TOOL_GROUP);
            reportTool.reset(); // 开闸，本轮汇报从零开始

            // 工具调用指纹由 ToolFingerprintHook 在 PreActing 阶段采集（工具名+入参，见其注释）；
            // 每轮新建 orchestrator + 独立记忆（轮间互不串记忆），单一来源见 ToolkitAssembler
            List<String> toolCalls = java.util.Collections.synchronizedList(new ArrayList<>());
            ReActAgent orchestrator = ToolkitAssembler.buildHeadlessOrchestrator(
                    runtime, baseSystemPrompt + routedPrompt, roundToolkit,
                    new ToolFingerprintHook(toolCalls));
            Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").textContent(prompt).build();

            // 截获本轮最终回复与用量：Reply 片段累加成完整回复（含结尾判定行），全部透传给真实 UI
            StringBuilder reply = new StringBuilder();
            ConversationCallbacks capturing = new ConversationCallbacks() {
                @Override
                public void onEvent(ConversationEvent event) {
                    if (event instanceof ConversationEvent.Reply r) {
                        reply.append(r.chunk());
                    } else if (event instanceof ConversationEvent.Usage u) {
                        inTokens.addAndGet(u.inputTokens());
                        outTokens.addAndGet(u.outputTokens());
                    }
                    outer.onEvent(event);
                }

                @Override
                public void onTerminal(ConversationOutcome outcome) {
                    /* 本轮结束由 runner 的 latch 控制，不透传 */
                }
            };

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> err = new AtomicReference<>();
            // doFinally 只清「本轮自己」的订阅引用：超时轮的 doFinally 可能迟到（取消信号
            // 排在被阻塞的 boundedElastic worker 之后），下一轮已 set 新订阅时无条件 clear()
            // 会抹掉新订阅，令 shutdown()/停止按钮的 dispose 扑空、在途轮杀不掉且单活跃闸不释放
            AtomicReference<Disposable> self = new AtomicReference<>();
            Disposable sub = orchestrator.stream(userMsg, streamOptions)
                    .subscribeOn(Schedulers.boundedElastic())
                    // doFinally 也要放闩：外部 dispose（取消循环）不会触发 onError/onComplete，
                    // 少了这里的 countDown 会让 runOnce 阻塞满整个单轮超时才返回
                    .doFinally(signal -> {
                        subscription.clearIf(self.get());
                        done.countDown();
                    })
                    // 放闩单点在 doFinally（onError/onComplete/外部 dispose 三类终结都必经，
                    // 且晚于本处理器执行，err.set 与 await 之间的可见性由闩保证），
                    // 处理器内不再各自 countDown
                    .subscribe(
                            event -> eventHandler.handleEvent(event, capturing),
                            err::set,
                            () -> { });
            self.set(sub);
            subscription.set(sub);
            // 补杀窗口：shutdown() 若发生在订阅建立之前，其 dispose 扑了空——这里再查一次
            // 终止标志，把刚建立的订阅立即掐掉（doFinally 会放闩，下方 await 立刻返回）
            if (closed) {
                subscription.dispose();
                reportTool.close(); // 失败出口一律关闸：在途 loop_report 迟到写入不得污染下一轮
                return IterationResult.failed(inTokens.get(), outTokens.get());
            }

            // 失败出口一律带上已实际消耗的用量（由事件实时累计）：超时轮可能已烧掉
            // 数万 token，归零会让预算护栏与状态卡累计用量双双低估
            if (!done.await(iterationTimeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("循环单轮执行超时（>{}s），中断本轮", iterationTimeoutSeconds);
                subscription.dispose();
                reportTool.close();
                return IterationResult.failed(inTokens.get(), outTokens.get());
            }
            // 被 shutdown 掐断的轮：外部 dispose 只触发 doFinally（不走 onError），err 为 null，
            // 截到一半的回复若折成正常成功轮，控制器会照常跑完决策漏斗（真跑验证命令/验收员），
            // 取消要拖到漏斗结束才落地——必须折成失败轮立即让位
            if (closed) {
                reportTool.close();
                return IterationResult.failed(inTokens.get(), outTokens.get());
            }
            if (err.get() != null) {
                log.error("循环单轮执行出错", err.get());
                reportTool.close();
                return IterationResult.failed(inTokens.get(), outTokens.get());
            }
            com.javaclaw.loop.model.LoopReport report = reportTool.consume();
            if (report == null) {
                log.warn("执行体未按协议调用 {} 工具，降级到哨兵行解析", LoopConstants.REPORT_TOOL_NAME);
            }
            return IterationResult.ok(reply.toString(), inTokens.get(), outTokens.get(),
                    List.copyOf(toolCalls), report);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            reportTool.close();
            return IterationResult.failed(inTokens.get(), outTokens.get());
        } catch (Exception e) {
            log.error("循环单轮执行启动异常", e);
            reportTool.close();
            return IterationResult.failed(inTokens.get(), outTokens.get());
        }
    }

    /**
     * 按目标原文路由工具组；成功结果跨轮缓存（目标不变，路由结果稳定），失败降级全量且不缓存
     * （下轮可重试）。
     */
    private RoutingResult route() {
        if (toolRouter == null || routingText.isBlank()) return RoutingResult.fallbackAll();
        RoutingResult cached = cachedRouting;
        if (cached != null) return cached;
        try {
            RoutingResult result = toolRouter.route(routingText);
            if (!result.isFallback()) {
                cachedRouting = result;
            }
            return result;
        } catch (Exception e) {
            log.warn("循环工具路由失败，降级全量: {}", e.getMessage());
            return RoutingResult.fallbackAll();
        }
    }

    /**
     * 工具指纹采集钩子：在 PreActing 阶段记录本轮「工具名+入参」指纹，供 ProgressGate
     * 比对「行动是否有新意」。
     *
     * <p>指纹刻意取<b>入参</b>而非结果——同一命令的输出天然含耗时/时间戳/PID 等噪声，
     * 掺入结果哈希会让原样重放的行动每轮都显得「有新意」，停滞护栏（NO_PROGRESS）
     * 永远不触发。协议工具 loop_report 不算「行动」，排除在指纹外。</p>
     */
    private static final class ToolFingerprintHook implements io.agentscope.core.hook.Hook {
        private final List<String> sink;

        ToolFingerprintHook(List<String> sink) {
            this.sink = sink;
        }

        @Override
        public <T extends io.agentscope.core.hook.HookEvent> reactor.core.publisher.Mono<T> onEvent(T event) {
            if (event instanceof io.agentscope.core.hook.PreActingEvent pre && pre.getToolUse() != null) {
                String name = pre.getToolUse().getName();
                if (!LoopConstants.REPORT_TOOL_NAME.equals(name)) {
                    Object input = pre.getToolUse().getInput();
                    sink.add(name + "#" + Integer.toHexString(
                            input == null ? 0 : String.valueOf(input).hashCode()));
                }
            }
            return reactor.core.publisher.Mono.just(event);
        }
    }

    /** 释放资源（循环结束/取消时调用）：先置终止标志再 dispose，兜住订阅尚未建立的窗口。 */
    public void shutdown() {
        closed = true;
        subscription.dispose();
        reportTool.close(); // 被掐断轮的在途汇报不再接收
        // 关闭本循环专属浏览器（懒启动，未用过则 shutdown 内部各 null 检查直接跳过）。
        // 必须放后台线程：本方法会经「停止按钮 → cancelLoopMode → LoopService.cancelActive」
        // 在 FX 线程上被调用，而 PlaywrightBrowserManager 的方法是 synchronized——在途轮
        // 正持锁做浏览器操作（如 Chromium 冷启动，数秒）时同步关闭会让 FX 线程阻塞到锁
        // 释放再加全套关闭 IPC，UI 冻结数秒到十余秒；浏览器是本循环私有资源，延迟关闭无碍。
        // 异步不保证在 JVM 退出前执行——优雅落地由循环 run 线程的 finally 调
        // closeBrowserSync() 兜底（见 LoopService.start），两者对幂等的 shutdown 重复调用无害
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(this::closeBrowserQuietly);
    }

    /**
     * 同步关闭隔离浏览器：供循环 run 线程的收尾 finally（后台线程，非 FX）调用，
     * 保证返回前浏览器优雅落地——纯异步关闭在应用退出路径上可能来不及执行
     * （boundedElastic 是守护线程，JVM 退出直接跳过队列任务，Chromium 未优雅关闭
     * 会残留临时数据目录甚至孤儿进程）。
     */
    public void closeBrowserSync() {
        closeBrowserQuietly();
    }

    private void closeBrowserQuietly() {
        try {
            loopBrowser.shutdown();
        } catch (Exception e) {
            log.warn("关闭循环隔离浏览器失败: {}", e.getMessage());
        }
    }
}
