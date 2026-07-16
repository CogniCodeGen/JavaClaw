package com.javaclaw.agent.expert;

import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.model.ModelFactory;
import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.browser.PlaywrightBrowserTools;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.prompt.AgentPrompts;
import com.javaclaw.desktop.DesktopTools;
import com.javaclaw.email.EmailTools;
import com.javaclaw.notification.NotificationTools;
import com.javaclaw.system.CommandLineTools;
import com.javaclaw.system.SystemTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.javaclaw.agent.expert.CustomAgentConfig.CustomAgentDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 专家智能体管理器 — 统一创建和管理所有子智能体
 *
 * <p>通过 {@link ExpertDef} 定义驱动专家创建，消除独立 Expert 类的样板代码。
 * 同时服务普通模式（AgentService，包装为 SubAgentTool）和规划模式
 * （PlanModeService，创建裸 ReActAgent）。</p>
 *
 * <p>KnowledgeExpert 因包含 RAG 知识库复杂逻辑，仍保持独立。</p>
 *
 * <p>内置专家在 {@link #buildExpertDefs} 中定义，自定义专家从
 * {@link CustomAgentConfig} 加载（用户通过设置界面管理）。</p>
 *
 * @author JavaClaw
 */
public class ExpertManager {

    private static final Logger log = LoggerFactory.getLogger(ExpertManager.class);

    /**
     * 专家定义 — 描述一个子智能体的所有配置
     *
     * @param agentName   智能体名称
     * @param sysPrompt   系统提示词
     * @param toolName     子智能体工具名称（用于 SubAgentTool 注册）
     * @param description 子智能体描述
     * @param maxIters    最大迭代次数
     * @param tools       工具实例（null 表示纯推理型）——<b>整个专家共享同一实例</b>，跨会话复用。
     *                    子智能体 agent 本身每会话新建（隔离对话内存），但工具实例故意共享：像
     *                    {@code DesktopTools.lastElements}（@ref 映射）这类状态需在<b>同一会话的
     *                    多次委派间延续</b>（先 desktop_inspect 再 desktop_click_ref 常分属两次委派），
     *                    每会话新建工具会清空 @ref 令其失效。桌面本是物理单例（一套屏幕/鼠标/键盘），
     *                    并发桌面自动化本就无法共存，故共享实例的理论竞争无需为之牺牲顺序流的正确性
     * @param groupName   工具分组名（用于路由器按组激活/禁用）
     */
    public record ExpertDef(
            String agentName,
            String sysPrompt,
            String toolName,
            String description,
            int maxIters,
            Object tools,
            String groupName
    ) {}

    /** 所有专家定义（有序） */
    private final List<ExpertDef> expertDefs;

    /** 普通模式的 SubAgentTool 集合（toolName → SubAgentTool） */
    private final Map<String, SubAgentTool> subAgentTools = new LinkedHashMap<>();

    /** 能力 → 工具实例映射（供 DynamicTaskTool 按能力组合工具集），以本管理器的来源令牌构建 */
    private final Map<String, Object> capabilityTools = new LinkedHashMap<>();

    /** 浏览器管理器（能力工具工厂需要，跨次构建复用同一浏览器实例） */
    private final PlaywrightBrowserManager browserManager;

    /** 本管理器所属编排路径的调用来源令牌（构造期绑定，注入到全部带工具专家） */
    private final ToolCallOrigin origin;

    /**
     * 构造专家管理器并创建所有普通模式子智能体
     *
     * @param modelFactory    模型工厂
     * @param browserManager  浏览器管理器
     * @param origin          本编排路径的调用来源令牌（聊天=INTERACTIVE、定时=SCHEDULED、
     *                        循环=managedTask(loopId, workDir)），注入到全部带工具专家
     */
    public ExpertManager(ModelFactory modelFactory, PlaywrightBrowserManager browserManager,
                         ToolCallOrigin origin) {
        this.browserManager = browserManager;
        this.origin = origin == null ? ToolCallOrigin.UNKNOWN : origin;
        this.expertDefs = buildExpertDefs(browserManager, this.origin);

        // 能力 → 工具实例映射（供 DynamicTaskTool 使用）：直接复用专家定义里的同一批实例
        // （令牌相同），而非再 new 一套——否则不仅白付双份构造（含 AWT Robot），还会把
        // DesktopTools.lastElements 这类需跨委派延续的 @ref 状态劈成互不可见的两份
        // （桌面专家 desktop_inspect 产生的 @ref 对动态任务的 desktop_click_ref 失效）
        for (ExpertDef def : expertDefs) {
            if (def.tools() != null) {
                capabilityTools.put(def.groupName(), def.tools());
            }
        }

        // 创建内置专家
        for (ExpertDef def : expertDefs) {
            SubAgentTool tool = createSubAgentTool(modelFactory.createChatModel(), def);
            subAgentTools.put(def.toolName(), tool);
        }

        // 加载并创建自定义专家（自定义专家使用 toolName 作为分组名）
        List<CustomAgentDef> customAgents = CustomAgentConfig.getInstance().getEnabled();
        for (CustomAgentDef custom : customAgents) {
            ExpertDef def = new ExpertDef(
                    custom.name, custom.sysPrompt, custom.toolName,
                    custom.description, custom.maxIters, null, custom.toolName);
            SubAgentTool tool = createSubAgentTool(modelFactory.createChatModel(), def);
            subAgentTools.put(def.toolName(), tool);
        }

        // 同上：本构造器在定时任务路径逐 tick 执行，降为 debug 防刷屏
        log.debug("专家管理器已初始化，内置 {} 个 + 自定义 {} 个子智能体（来源={}）",
                expertDefs.size(), customAgents.size(), this.origin.kind());
    }

    /**
     * 构建所有专家定义
     *
     * <p>扩展新专家只需在此处添加一行 ExpertDef。</p>
     */
    private static List<ExpertDef> buildExpertDefs(PlaywrightBrowserManager browserManager,
                                                    ToolCallOrigin origin) {
        AgentConfig config = AgentConfig.getInstance();

        List<ExpertDef> defs = new ArrayList<>();

        // 纯推理型专家（无工具）
        defs.add(new ExpertDef(
                AgentConfig.CODING_AGENT_NAME,
                AgentPrompts.CODING_AGENT_SYS_PROMPT,
                "coding_expert",
                AgentConfig.CODING_AGENT_DESCRIPTION,
                1, null, "coding"));

        defs.add(new ExpertDef(
                AgentConfig.EVALUATOR_AGENT_NAME,
                AgentPrompts.EVALUATOR_AGENT_SYS_PROMPT,
                "task_evaluator",
                AgentConfig.EVALUATOR_AGENT_DESCRIPTION,
                1, null, "evaluator"));

        // 带工具的专家：工具实例整个专家共享、跨会话复用（见 ExpertDef.tools 注释——@ref 等
        // 会话内多次委派间延续的状态需要共享实例，每会话新建会清空之）
        defs.add(new ExpertDef(
                AgentConfig.WEB_AGENT_NAME,
                AgentPrompts.WEB_AGENT_SYS_PROMPT,
                "web_expert",
                AgentConfig.WEB_AGENT_DESCRIPTION,
                config.getWebAgentMaxIters(),
                new PlaywrightBrowserTools(browserManager, origin), "web"));

        defs.add(new ExpertDef(
                AgentConfig.EMAIL_AGENT_NAME,
                AgentPrompts.EMAIL_AGENT_SYS_PROMPT,
                "email_expert",
                AgentConfig.EMAIL_AGENT_DESCRIPTION,
                config.getEmailAgentMaxIters(),
                new EmailTools(origin), "email"));

        defs.add(new ExpertDef(
                AgentConfig.SYSTEM_AGENT_NAME,
                AgentPrompts.SYSTEM_AGENT_SYS_PROMPT,
                "system_expert",
                AgentConfig.SYSTEM_AGENT_DESCRIPTION,
                config.getSystemAgentMaxIters(),
                new SystemTools(origin), "system"));

        defs.add(new ExpertDef(
                AgentConfig.DESKTOP_AGENT_NAME,
                AgentPrompts.DESKTOP_AGENT_SYS_PROMPT,
                "desktop_expert",
                AgentConfig.DESKTOP_AGENT_DESCRIPTION,
                config.getSystemAgentMaxIters(),
                new DesktopTools(origin), "desktop"));

        defs.add(new ExpertDef(
                AgentConfig.NOTIFICATION_AGENT_NAME,
                AgentPrompts.NOTIFICATION_AGENT_SYS_PROMPT,
                "notification_expert",
                AgentConfig.NOTIFICATION_AGENT_DESCRIPTION,
                config.getNotificationAgentMaxIters(),
                new NotificationTools(origin), "notification"));

        defs.add(new ExpertDef(
                AgentConfig.COMMAND_AGENT_NAME,
                AgentPrompts.COMMAND_AGENT_SYS_PROMPT,
                "command_expert",
                AgentConfig.COMMAND_AGENT_DESCRIPTION,
                config.getCommandAgentMaxIters(),
                new CommandLineTools(origin), "command"));

        return defs;
    }

    /**
     * 按来源令牌构建一套全新的能力 → 工具实例映射。
     *
     * <p>专供 SDD 任务启动时逐任务调用（令牌带该任务的 taskId/workDir），使能力工具的
     * 高风险确认按任务归属走白名单/目录放行——共享实例无法承载逐任务变化的归属。
     * 本管理器自身的能力映射（{@link #getCapabilityTools}）不走此方法：它直接复用专家
     * 定义里的同一批工具实例（令牌相同，且 @ref 等跨委派状态必须同源，见构造器注释）。</p>
     */
    public Map<String, Object> buildCapabilityTools(ToolCallOrigin toolOrigin) {
        ToolCallOrigin effective = toolOrigin == null ? ToolCallOrigin.UNKNOWN : toolOrigin;
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("web", new PlaywrightBrowserTools(browserManager, effective));
        tools.put("email", new EmailTools(effective));
        tools.put("system", new SystemTools(effective));
        tools.put("desktop", new DesktopTools(effective));
        tools.put("notification", new NotificationTools(effective));
        tools.put("command", new CommandLineTools(effective));
        log.info("能力工具集已构建: {} (来源={})", tools.keySet(), effective.kind());
        return tools;
    }

    // ==================== 普通模式（AgentService 使用） ====================

    /**
     * 获取所有 SubAgentTool，用于注册到主编排器的 Toolkit
     */
    public List<SubAgentTool> getAllTools() {
        return new ArrayList<>(subAgentTools.values());
    }

    /**
     * 获取能力 → 工具实例映射（供 DynamicTaskTool 使用）
     */
    public Map<String, Object> getCapabilityTools() {
        return Map.copyOf(capabilityTools);
    }

    /**
     * 获取所有专家定义（供工具路由器构建提示词使用）
     */
    public List<ExpertDef> getExpertDefs() {
        return List.copyOf(expertDefs);
    }

    // ==================== 规划模式（PlanModeService 使用） ====================

    /**
     * 为规划模式创建所有专家的 ReActAgent（使用 MultiAgent 模型 + 规划后缀提示词）
     *
     * @param modelFactory 模型工厂（用于创建 MultiAgent 模型）
     * @return 专家名称 → ReActAgent 映射（有序）
     */
    public Map<String, ReActAgent> createPlanModeAgents(ModelFactory modelFactory) {
        String suffix = AgentPrompts.PLAN_MODE_EXPERT_SUFFIX;
        Map<String, ReActAgent> agents = new LinkedHashMap<>();

        for (ExpertDef def : expertDefs) {
            ReActAgent agent = createAgent(
                    modelFactory.createMultiAgentChatModel(),
                    def.agentName(),
                    def.sysPrompt() + suffix,
                    def.maxIters(),
                    def.tools());
            agents.put(def.agentName(), agent);
        }

        // 规划模式额外添加知识专家（纯推理，无 RAG 工具）
        agents.put(AgentConfig.KNOWLEDGE_AGENT_NAME, createAgent(
                modelFactory.createMultiAgentChatModel(),
                AgentConfig.KNOWLEDGE_AGENT_NAME,
                AgentPrompts.KNOWLEDGE_AGENT_SYS_PROMPT + suffix,
                1, null));

        // 加载自定义专家到规划模式
        for (CustomAgentDef custom : CustomAgentConfig.getInstance().getEnabled()) {
            agents.put(custom.name, createAgent(
                    modelFactory.createMultiAgentChatModel(),
                    custom.name,
                    custom.sysPrompt + suffix,
                    custom.maxIters, null));
        }

        log.info("规划模式专家已创建: {} 个", agents.size());
        return agents;
    }

    // ==================== 内部创建方法 ====================

    /**
     * 创建 SubAgentTool（普通模式使用）
     */
    private static SubAgentTool createSubAgentTool(ChatModelBase model, ExpertDef def) {
        SubAgentConfig config = SubAgentConfig.builder()
                .toolName(def.toolName())
                .description(def.description())
                .forwardEvents(true)
                .build();

        // 每次子智能体调用构建全新 ReActAgent 实例：对齐 SubAgentTool 的线程安全契约——其文档
        // 要求 provider.provide() 每会话返回独立实例，此前的 () -> 单例写法让并行调用（如循环
        // 与聊天同时委派同一专家）共享同一 agent 对话内存而竞争。工具实例（def.tools()）则故意
        // 跨会话共享：像 DesktopTools 的 @ref 映射需在同一会话多次委派间延续，每会话新建会清空它
        // （见 ExpertDef.tools 注释）；被隔离的只有各调用的对话内存。
        SubAgentTool tool = new SubAgentTool(
                () -> createAgent(model, def.agentName(), def.sysPrompt(), def.maxIters(), def.tools()),
                config);
        // 定时/循环路径逐 run 重建专家集（令牌归属），info 会每 tick 刷屏，降为 debug
        log.debug("已注册子智能体工具: {}", def.toolName());
        return tool;
    }

    /**
     * 创建 ReActAgent 实例
     */
    private static ReActAgent createAgent(ChatModelBase model, String agentName,
                                          String sysPrompt, int maxIters, Object tools) {
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(agentName)
                .sysPrompt(sysPrompt)
                .model(model)
                .maxIters(maxIters);

        if (tools != null) {
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(tools);
            builder.toolkit(toolkit);
        }

        ReActAgent agent = builder.build();
        // 普通模式下每次子智能体调用都会构建全新实例（见 createSubAgentTool），故降为 debug 避免刷屏
        log.debug("智能体已创建: {}, maxIters: {}", agentName, maxIters);
        return agent;
    }
}
