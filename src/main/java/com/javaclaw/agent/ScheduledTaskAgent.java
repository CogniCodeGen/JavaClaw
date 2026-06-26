package com.javaclaw.agent;

import com.javaclaw.agent.expert.DynamicTaskTool;
import com.javaclaw.agent.expert.ExpertManageTools;
import com.javaclaw.agent.expert.ExpertManager;
import com.javaclaw.agent.handler.StreamEventHandler;
import com.javaclaw.agent.hook.LoopDetectionHook;
import com.javaclaw.agent.router.RoutingResult;
import com.javaclaw.agent.router.ToolRouter;
import com.javaclaw.api.conversation.ConversationCallbacks;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.prompt.AgentPrompts;
import com.javaclaw.mcp.McpTools;
import com.javaclaw.skill.SkillManager;
import com.javaclaw.util.AtomicDisposable;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务专用编排器 —— 与交互 {@link ChatService} <b>完全隔离</b>的独立执行体。
 *
 * <p>定时任务执行若复用交互 {@code ChatService} 的单 orchestrator + 单 activeSubscription，
 * 两者并发时会互相 dispose 订阅导致聊天卡死、定时 tick 丢失。本类自带：
 * <ul>
 *   <li>独立 {@link ExpertManager}（一套独立子智能体，避免与交互编排器并发调用同一子智能体）；</li>
 *   <li>独立 {@link Toolkit}（独立的工具分组激活状态，不与交互 toolkit 抢 setActiveGroups）；</li>
 *   <li>每次执行独立的 orchestrator + {@link AutoContextMemory}（每个 tick 互不串记忆）；</li>
 *   <li>独立订阅。</li>
 * </ul>
 * 故定时任务可与交互聊天<b>真正并行</b>，互不影响。</p>
 *
 * <p>刻意精简：不挂视觉预处理 / GoalManager / GEPA 评估 / 记忆蒸馏 / 技能蒸馏 / 澄清工具
 * （定时任务无人在场、prompt 自包含）。保留工具路由、技能目录注入与循环检测（无人值守更需防失控）。</p>
 *
 * <p>线程模型：{@link #run} 阻塞直到本次流式完成（或超时），由 {@code ScheduleManager} 的单线程
 * 执行器串行调用——保证同一时刻只有一个定时执行，复用的 toolkit / 子智能体不被并发触碰。</p>
 */
public final class ScheduledTaskAgent {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskAgent.class);

    /** 单次定时执行的硬上限，防止某次 agent 跑挂把串行执行器永久堵死 */
    private static final long RUN_TIMEOUT_MINUTES = 12;

    private final AgentRuntime runtime;
    private final ExpertManager expertManager;
    private final Toolkit toolkit;
    private final StreamEventHandler eventHandler = new StreamEventHandler();
    private final ToolRouter toolRouter;
    private final String baseSystemPrompt;
    private final StreamOptions streamOptions;
    private final AtomicDisposable subscription = new AtomicDisposable();

    public ScheduledTaskAgent(AgentRuntime runtime) {
        this.runtime = runtime;
        AgentConfig config = AgentConfig.getInstance();
        // 独立子智能体集合（不复用 runtime.getExpertManager()，避免与交互编排器并发调用同一子智能体）
        this.expertManager = new ExpertManager(runtime.getModelFactory(), runtime.getBrowserManager());
        this.toolkit = buildToolkit();
        this.toolRouter = config.isToolRoutingEnabled()
                ? new ToolRouter(runtime.getModelFactory().createLightChatModel(), runtime.getTokenTracker())
                : null;
        this.baseSystemPrompt = AgentPrompts.ORCHESTRATOR_SYS_PROMPT;

        StreamOptions.Builder sb = StreamOptions.builder().incremental(true);
        if (config.isThinkingEnabled()) {
            sb.includeReasoningChunk(true).includeReasoningResult(false)
                    .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.HINT, EventType.AGENT_RESULT);
        } else {
            sb.eventTypes(EventType.TOOL_RESULT, EventType.HINT, EventType.AGENT_RESULT);
        }
        this.streamOptions = sb.build();
        log.info("定时任务专用编排器已创建（独立子智能体 + 独立 toolkit）");
    }

    /** 构建独立工具集（与 ChatService.buildMasterToolkit 同构，但不含交互专属的 clarify 工具）。 */
    private Toolkit buildToolkit() {
        Toolkit tk = new Toolkit();
        for (String group : RoutingResult.ALL_TOOL_GROUPS) {
            tk.createToolGroup(group, group, true);
        }
        for (var def : expertManager.getExpertDefs()) {
            var tool = expertManager.getAllTools().stream()
                    .filter(t -> t.getName().equals(def.toolName())).findFirst().orElse(null);
            if (tool != null) tk.registration().agentTool(tool).group(def.groupName()).apply();
        }
        tk.createToolGroup("agents", "agents", true);
        for (var tool : expertManager.getAllTools()) {
            if (!tk.getToolNames().contains(tool.getName())) {
                tk.registration().agentTool(tool).group("agents").apply();
            }
        }
        tk.registration().agentTool(runtime.getKnowledgeExpert().getTool()).group("knowledge").apply();
        tk.registration().tool(new DynamicTaskTool(runtime.getModelFactory(),
                runtime.getMemoryManager(), runtime.getCapabilityTools())).group("dynamic_task").apply();
        tk.registration().tool(new McpTools(runtime.getMcpClientManager())).group("mcp").apply();
        // skill 组不在 ALL_TOOL_GROUPS 中（属 ALWAYS_ACTIVE_GROUPS，不参与路由），需手动建组
        tk.createToolGroup("skill", "skill", true);
        tk.registration().tool(new com.javaclaw.skill.SkillTools()).group("skill").apply();
        tk.registration().tool(new com.javaclaw.skill.SkillManageTools()).group("skill").apply();
        tk.registration().tool(new com.javaclaw.system.JShellTools()).group("skill").apply();
        tk.registration().tool(new com.javaclaw.task.sdd.run.SddTaskManageTools()).group("task_manage").apply();
        tk.registration().tool(new com.javaclaw.schedule.ScheduleTools()).group("schedule").apply();
        tk.registration().tool(new ExpertManageTools(expertManager)).group("agents").apply();
        return tk;
    }

    /**
     * 执行一条定时提示词，<b>阻塞</b>直到流式完成 / 出错 / 超时。
     *
     * <p>由 {@code ScheduleManager} 的单线程执行器串行调用：阻塞语义保证同一时刻仅一个定时执行，
     * 复用的 toolkit / 子智能体不被并发触碰。</p>
     */
    public void run(String prompt, ConversationCallbacks callbacks) {
        try {
            RoutingResult routing = route(prompt);
            List<String> groups = (routing.isFallback() || !routing.hasToolGroups())
                    ? new ArrayList<>(RoutingResult.ALL_TOOL_GROUPS)
                    : new ArrayList<>(routing.toolGroups());
            // 技能目录读取（skill_read）与智能体组始终可用，与路由解耦
            if (!groups.contains("skill")) groups.add("skill");
            if (!groups.contains("agents")) groups.add("agents");
            toolkit.setActiveGroups(groups);

            String skillCatalog = SkillManager.getInstance().buildSkillCatalogPrompt(new HashSet<>(groups));
            String skillsPrompt = (routing.isAllSkills() || routing.isFallback())
                    ? SkillManager.getInstance().buildEnabledSkillsPrompt()
                    : SkillManager.getInstance().buildFilteredSkillsPrompt(routing.skillNames());
            String mcpPrompt = (routing.isAllMcp() || routing.isFallback())
                    ? runtime.getMcpClientManager().buildToolsPrompt()
                    : runtime.getMcpClientManager().buildFilteredToolsPrompt(routing.mcpServers());

            ReActAgent orchestrator = buildOrchestrator(baseSystemPrompt + skillCatalog + skillsPrompt + mcpPrompt);
            Msg userMsg = Msg.builder().role(MsgRole.USER).name("user").textContent(prompt).build();

            CountDownLatch done = new CountDownLatch(1);
            Disposable sub = orchestrator.stream(userMsg, streamOptions)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doFinally(signal -> subscription.clear())
                    .subscribe(
                            event -> eventHandler.handleEvent(event, callbacks),
                            error -> {
                                log.error("定时任务编排执行出错", error);
                                callbacks.onError(error);
                                done.countDown();
                            },
                            () -> {
                                callbacks.onComplete();
                                done.countDown();
                            });
            subscription.set(sub);

            if (!done.await(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                log.warn("定时任务执行超时（>{}分钟），强制中断本次", RUN_TIMEOUT_MINUTES);
                subscription.dispose();
                callbacks.onError(new java.util.concurrent.TimeoutException(
                        "定时任务执行超过 " + RUN_TIMEOUT_MINUTES + " 分钟，已中断"));
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            callbacks.onError(ie);
        } catch (Exception e) {
            log.error("定时任务编排启动异常", e);
            callbacks.onError(e);
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

    /** 每次执行新建 orchestrator + 独立记忆（tick 之间互不串记忆），挂循环检测防失控。 */
    private ReActAgent buildOrchestrator(String sysPrompt) {
        AgentConfig config = AgentConfig.getInstance();
        AutoContextConfig memCfg = AutoContextConfig.builder()
                .maxToken(config.getMemoryMaxToken())
                .msgThreshold(config.getMemoryMsgThreshold())
                .lastKeep(config.getMemoryLastKeep())
                .tokenRatio(config.getMemoryTokenRatio())
                .build();
        return ReActAgent.builder()
                .name(AgentConfig.AGENT_NAME)
                .sysPrompt(sysPrompt)
                .model(runtime.getModelFactory().createHighChatModel())
                .toolkit(toolkit)
                .memory(new AutoContextMemory(memCfg, runtime.getModelFactory().createChatModel()))
                .modelExecutionConfig(runtime.getModelExecConfig())
                .maxIters(config.getOrchestratorMaxIters())
                .enablePlan()
                .hooks(List.of(new LoopDetectionHook()))
                .build();
    }

    /** 释放资源（应用退出 / 工作区切换重建时调用）。 */
    public void shutdown() {
        subscription.dispose();
    }
}
