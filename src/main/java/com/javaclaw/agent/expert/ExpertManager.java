package com.javaclaw.agent.expert;

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
     * @param toolName    子智能体工具名称（用于 SubAgentTool 注册）
     * @param description 子智能体描述
     * @param maxIters    最大迭代次数
     * @param tools       工具实例（null 表示纯推理型）
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

    /** 能力 → 工具实例映射（供 DynamicTaskTool 按能力组合工具集） */
    private final Map<String, Object> capabilityTools = new LinkedHashMap<>();

    /**
     * 构造专家管理器并创建所有普通模式子智能体
     *
     * @param modelFactory    模型工厂
     * @param browserManager  浏览器管理器
     */
    public ExpertManager(ModelFactory modelFactory, PlaywrightBrowserManager browserManager) {
        this.expertDefs = buildExpertDefs(browserManager);

        // 构建能力 → 工具实例映射（供 DynamicTaskTool 使用）
        buildCapabilityTools(browserManager);

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

        log.info("专家管理器已初始化，内置 {} 个 + 自定义 {} 个子智能体",
                expertDefs.size(), customAgents.size());
    }

    /**
     * 构建所有专家定义
     *
     * <p>扩展新专家只需在此处添加一行 ExpertDef。</p>
     */
    private static List<ExpertDef> buildExpertDefs(PlaywrightBrowserManager browserManager) {
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

        // 带工具的专家
        defs.add(new ExpertDef(
                AgentConfig.WEB_AGENT_NAME,
                AgentPrompts.WEB_AGENT_SYS_PROMPT,
                "web_expert",
                AgentConfig.WEB_AGENT_DESCRIPTION,
                config.getWebAgentMaxIters(),
                new PlaywrightBrowserTools(browserManager), "web"));

        defs.add(new ExpertDef(
                AgentConfig.EMAIL_AGENT_NAME,
                AgentPrompts.EMAIL_AGENT_SYS_PROMPT,
                "email_expert",
                AgentConfig.EMAIL_AGENT_DESCRIPTION,
                config.getEmailAgentMaxIters(),
                new EmailTools(), "email"));

        defs.add(new ExpertDef(
                AgentConfig.SYSTEM_AGENT_NAME,
                AgentPrompts.SYSTEM_AGENT_SYS_PROMPT,
                "system_expert",
                AgentConfig.SYSTEM_AGENT_DESCRIPTION,
                config.getSystemAgentMaxIters(),
                new SystemTools(), "system"));

        defs.add(new ExpertDef(
                AgentConfig.DESKTOP_AGENT_NAME,
                AgentPrompts.DESKTOP_AGENT_SYS_PROMPT,
                "desktop_expert",
                AgentConfig.DESKTOP_AGENT_DESCRIPTION,
                config.getSystemAgentMaxIters(),
                new DesktopTools(), "desktop"));

        defs.add(new ExpertDef(
                AgentConfig.NOTIFICATION_AGENT_NAME,
                AgentPrompts.NOTIFICATION_AGENT_SYS_PROMPT,
                "notification_expert",
                AgentConfig.NOTIFICATION_AGENT_DESCRIPTION,
                config.getNotificationAgentMaxIters(),
                new NotificationTools(), "notification"));

        defs.add(new ExpertDef(
                AgentConfig.COMMAND_AGENT_NAME,
                AgentPrompts.COMMAND_AGENT_SYS_PROMPT,
                "command_expert",
                AgentConfig.COMMAND_AGENT_DESCRIPTION,
                config.getCommandAgentMaxIters(),
                new CommandLineTools(), "command"));

        return defs;
    }

    /**
     * 构建能力 → 工具实例映射
     *
     * <p>DynamicTaskTool 根据编排器指定的能力名按需组合工具集。
     * 工具实例在此处统一创建，避免重复实例化。</p>
     */
    private void buildCapabilityTools(PlaywrightBrowserManager browserManager) {
        capabilityTools.put("web", new PlaywrightBrowserTools(browserManager));
        capabilityTools.put("email", new EmailTools());
        capabilityTools.put("system", new SystemTools());
        capabilityTools.put("desktop", new DesktopTools());
        capabilityTools.put("notification", new NotificationTools());
        capabilityTools.put("command", new CommandLineTools());
        log.info("能力工具注册表已构建: {}", capabilityTools.keySet());
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
        ReActAgent agent = createAgent(model, def.agentName(), def.sysPrompt(),
                def.maxIters(), def.tools());

        SubAgentConfig config = SubAgentConfig.builder()
                .toolName(def.toolName())
                .description(def.description())
                .forwardEvents(true)
                .build();

        SubAgentTool tool = new SubAgentTool(() -> agent, config);
        log.info("已注册子智能体工具: {}", def.toolName());
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
        log.info("智能体已创建: {}, maxIters: {}", agentName, maxIters);
        return agent;
    }
}
