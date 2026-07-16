package com.javaclaw.agent;

import com.javaclaw.agent.expert.ExpertManager;
import com.javaclaw.agent.handler.StreamEventHandler;
import com.javaclaw.agent.router.RoutingResult;
import com.javaclaw.agent.router.ToolRouter;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.config.AgentConfig;
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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务专用编排器 —— 与交互 {@link ChatService} <b>完全隔离</b>的独立执行体。
 *
 * <p>定时任务执行若复用交互 {@code ChatService} 的单 orchestrator + 单 activeSubscription，
 * 两者并发时会互相 dispose 订阅导致聊天卡死、定时 tick 丢失。本类每次执行自带：
 * <ul>
 *   <li>逐 run 全新 {@link ExpertManager}（独立子智能体，避免与交互编排器并发调用同一子智能体；
 *       来源令牌为本次 run 专属实例，授权窗按实例身份匹配——工具集因此<b>不可</b>跨 run 复用）；</li>
 *   <li>逐 run 全新 {@link Toolkit}（独立的工具分组激活状态，不与交互 toolkit 抢 setActiveGroups，
 *       也不与上一次执行的残存线程共享带旧令牌的工具实例）；</li>
 *   <li>每次执行独立的 orchestrator + {@link AutoContextMemory}（每个 tick 互不串记忆）；</li>
 *   <li>独立订阅。</li>
 * </ul>
 * 故定时任务可与交互聊天<b>真正并行</b>，互不影响。</p>
 *
 * <p>刻意精简：不挂视觉预处理 / GoalManager / GEPA 评估 / 记忆蒸馏 / 技能蒸馏 / 澄清工具
 * （定时任务无人在场、prompt 自包含）。保留工具路由、技能目录注入与循环检测（无人值守更需防失控）。</p>
 *
 * <p>线程模型：{@link #run} 阻塞直到本次流式完成（或超时），由 {@code ScheduleManager} 的单线程
 * 执行器串行调用——保证同一时刻只有一个定时执行。</p>
 */
public final class ScheduledTaskAgent {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskAgent.class);

    /** 单次定时执行的硬上限，防止某次 agent 跑挂把串行执行器永久堵死 */
    private static final long RUN_TIMEOUT_MINUTES = 12;

    private final AgentRuntime runtime;
    private final StreamEventHandler eventHandler = new StreamEventHandler();
    private final ToolRouter toolRouter;
    private final String baseSystemPrompt;
    private final StreamOptions streamOptions;
    private final AtomicDisposable subscription = new AtomicDisposable();

    /**
     * 终止标志：{@link #shutdown()} 可能落在 run() 的「逐 run 装配（含数秒级阻塞的路由模型
     * 调用）与 subscribe 之间」的窗口——此时 dispose 命中空引用，单靠它会漏杀随后建立的流，
     * 被换掉的旧编排器带着旧工作区目录/凭据继续执行 12 分钟。置位后 run() 在装配前与订阅后
     * 各查一次，兜住窗口（与 {@code AgentScopeLoopRunner.closed} 同一模式）。
     */
    private volatile boolean closed;

    public ScheduledTaskAgent(AgentRuntime runtime) {
        this.runtime = runtime;
        AgentConfig config = AgentConfig.getInstance();
        this.toolRouter = config.isToolRoutingEnabled()
                ? new ToolRouter(runtime.getModelFactory().createLightChatModel(), runtime.getTokenTracker())
                : null;
        this.baseSystemPrompt = AgentPrompts.ORCHESTRATOR_SYS_PROMPT;

        this.streamOptions = ToolkitAssembler.buildStreamOptions(config);
        log.info("定时任务专用编排器已创建（子智能体/toolkit 逐次执行按任务令牌装配）");
    }

    /**
     * 执行一条定时提示词，<b>阻塞</b>直到流式完成 / 出错 / 超时。
     *
     * <p>由 {@code ScheduleManager} 的单线程执行器串行调用：阻塞语义保证同一时刻仅一个定时执行。
     * 子智能体集合与 toolkit <b>逐 run 全新装配</b>（纯内存接线，无 LLM/IO），全部工具绑定
     * 调用方传入的<b>本次 run 专属</b>来源令牌——确认层按令牌实例身份（==）匹配授权窗，
     * 上一次执行超时残存的僵尸线程携带旧 run 的令牌实例（即便同一任务的下个 tick），结构上
     * 不可能借授权放行；这也是工具集必须逐 run 重建而不能跨 run 复用的原因（复用实例即复用
     * 旧令牌，令牌身份防线随之失效）。同时逐 run 装配也不与交互编排器共用任何子智能体/toolkit，
     * 可与聊天真正并行。</p>
     *
     * @param origin 本次执行的来源令牌（由 {@code ToolConfirmationManager.beginAuthorizedScheduledRun}
     *               构造，授权窗与工具装配共享同一实例；插件等无授权窗路径可自行构造）
     */
    public void run(ToolCallOrigin origin, String prompt, ConversationCallbacks callbacks) {
        // 终结回调「恰好一次」闸：onError / onComplete / 超时 / 外部 dispose / 线程中断 / 装配异常
        // 全部终结路径由 CAS 决出唯一胜者——声明在 try 之外，catch 出口也必须过同一闸
        // （中断可能与流正常完成竞争，绕过闸会让同一 tick 触发「成功+失败」两次终结回调：
        // run_count/fail_count 双双错增、写入矛盾的执行记录、向用户发出相互矛盾的两条通知）
        java.util.concurrent.atomic.AtomicBoolean signaled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            if (origin == null) {
                origin = ToolCallOrigin.SCHEDULED;
            }
            // 终止检查①：shutdown() 已发生（服务重建/切工作区）——绑定旧工作区资源的装配
            // 不再启动，记为取消（可审计）而非静默吞掉
            if (closed) {
                if (signaled.compareAndSet(false, true)) {
                    callbacks.onError(new java.util.concurrent.CancellationException(
                            "定时编排器已关闭，本次执行未启动"));
                }
                return;
            }
            // 独立子智能体集合（不复用 runtime.getExpertManager()，避免与交互编排器并发调用同一子智能体）；
            // 无人值守路径：不含交互专属的 clarify 工具与会弹桌面窗口的媒体工具（view_image）
            ExpertManager expertManager = new ExpertManager(
                    runtime.getModelFactory(), runtime.getBrowserManager(), origin);
            Toolkit toolkit = ToolkitAssembler.buildBaseToolkit(runtime, expertManager, false, origin);

            RoutingResult routing = route(prompt);
            // 激活组 + 拼提示词的每轮装配走单一来源（与循环路径共用），此处不再各持拷贝
            String routedPrompt = ToolkitAssembler.activateRoutedGroups(toolkit, routing, runtime);

            // 每次执行独立 orchestrator + 独立记忆（tick 之间互不串记忆），单一来源见 ToolkitAssembler
            ReActAgent orchestrator = ToolkitAssembler.buildHeadlessOrchestrator(
                    runtime, baseSystemPrompt + routedPrompt, toolkit);
            Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").textContent(prompt).build();

            CountDownLatch done = new CountDownLatch(1);
            // clearIf 而非无条件 clear：本轮超时被 dispose 后，阻塞 worker 上的 doFinally 可能迟到——
            // 若无条件 clear 会抹掉「下一次执行」刚 set 的订阅引用，令后续超时 dispose 扑空、
            // 该次编排流失去 12 分钟上限约束（与 ChatService/AgentScopeLoopRunner 的 clearIf 同一模式）
            final java.util.concurrent.atomic.AtomicReference<Disposable> selfSub =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Disposable sub = orchestrator.stream(userMsg, streamOptions)
                    .subscribeOn(Schedulers.boundedElastic())
                    // doFinally 也要放闩：外部 dispose 不触发 onError/onComplete，少了这里的
                    // countDown 会让 run() 阻塞满 12 分钟超时才返回——拖住串行执行器排队所有
                    // 后续 tick（与 AgentScopeLoopRunner 的 doFinally 放闩同一模式）。
                    // 放闩单点在此，处理器内不再各自 countDown
                    .doFinally(signal -> {
                        subscription.clearIf(selfSub.get());
                        done.countDown();
                    })
                    .subscribe(
                            event -> eventHandler.handleEvent(event, callbacks),
                            error -> {
                                log.error("定时任务编排执行出错", error);
                                if (signaled.compareAndSet(false, true)) {
                                    callbacks.onError(error);
                                }
                            },
                            () -> {
                                if (signaled.compareAndSet(false, true)) {
                                    callbacks.onComplete();
                                }
                            });
            selfSub.set(sub);
            subscription.set(sub);
            // 终止检查②（补杀窗口）：shutdown() 若落在「装配（含数秒级阻塞的路由模型调用）
            // 与 subscribe 之间」，其 dispose 命中的是空引用——这里复查一次终止标志，把刚
            // 建立的订阅立即掐掉（doFinally 放闩，下方 await 立刻返回并按取消记录），否则
            // 该 tick 会带着旧工作区的目录/凭据继续跑满 12 分钟、无人能停
            // （与 AgentScopeLoopRunner 的 closed 双检同一模式）
            if (closed) {
                subscription.dispose();
            }

            if (!done.await(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                log.warn("定时任务执行超时（>{}分钟），强制中断本次", RUN_TIMEOUT_MINUTES);
                subscription.dispose();
                if (signaled.compareAndSet(false, true)) {
                    callbacks.onError(new java.util.concurrent.TimeoutException(
                            "定时任务执行超过 " + RUN_TIMEOUT_MINUTES + " 分钟，已中断"));
                }
            } else if (signaled.compareAndSet(false, true)) {
                // 闩已放行但 onError/onComplete 均未触发 = 外部 dispose 中断：
                // 记为一次失败执行（含耗时/通知），保证任务历史可审计
                callbacks.onError(new java.util.concurrent.CancellationException(
                        "定时执行被中断（服务重建 / 工作区切换 / 应用关闭）"));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            if (signaled.compareAndSet(false, true)) {
                callbacks.onError(ie);
            }
        } catch (Exception e) {
            log.error("定时任务编排启动异常", e);
            if (signaled.compareAndSet(false, true)) {
                callbacks.onError(e);
            }
        }
    }

    private RoutingResult route(String prompt) {
        if (toolRouter == null) return RoutingResult.fallbackAll();
        try {
            return toolRouter.route(prompt);
        } catch (Exception e) {
            log.warn("定时任务工具路由失败，降级全量: {}", e.getMessage());
            return RoutingResult.fallbackAll();
        }
    }

    /** 释放资源（应用退出 / 工作区切换重建时调用）：先置终止标志再 dispose，兜住订阅尚未建立的窗口。 */
    public void shutdown() {
        closed = true;
        subscription.dispose();
    }
}
