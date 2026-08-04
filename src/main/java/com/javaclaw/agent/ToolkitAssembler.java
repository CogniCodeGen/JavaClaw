package com.javaclaw.agent;

import com.javaclaw.agent.expert.DynamicTaskTool;
import com.javaclaw.agent.expert.ExpertManageTools;
import com.javaclaw.agent.expert.ExpertManager;
import com.javaclaw.agent.hook.LoopDetectionHook;
import com.javaclaw.agent.router.RoutingResult;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.mcp.McpTools;
import com.javaclaw.prompt.AgentPrompts;
import com.javaclaw.util.ProjectAccessPolicy;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.tool.Toolkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 编排器工具集统一装配：聊天 / 定时任务 / 循环三条路径共用的「标准工具集」单一来源。
 *
 * <p>此前三处近逐字复制且已经分叉——plugins / media 组只有聊天路径注册，定时与循环
 * 路径即便路由命中也无工具可用（静默缺失，只在运行时暴露）。新增工具组或调整注册
 * 规则只改这里；各路径专属工具（聊天的 clarify、循环的 loop_report）由调用方在返回
 * 的 toolkit 上追加注册。</p>
 */
public final class ToolkitAssembler {

    private ToolkitAssembler() {}

    /**
     * 构建标准工具集：ALL_TOOL_GROUPS 建组 + 内置专家 + 常驻 agents 组（自定义/动态智能体）
     * + 知识库 + 动态任务 + MCP + 插件桥 + 技能三件套 + 长任务/定时管理（+ 可选媒体工具）。
     *
     * @param runtime        基础设施容器
     * @param expertManager  专家管理器（聊天用 runtime 共享实例；定时/循环用各自的独立实例）
     * @param withMediaTools 是否注册媒体工具（view_image / ocr_recognize）。仅交互路径为 true：
     *                       view_image 会经 UserInteractionPort 在桌面弹出图片查看窗口，
     *                       无人值守路径（定时任务/循环）注册它会在夜间弹出无人关闭的孤儿窗口
     * @param origin         本路径的调用来源令牌（注入到本方法直接构造的带确认工具；专家内的
     *                       工具令牌由 expertManager 构造期绑定，两者应传同一来源）
     */
    public static Toolkit buildBaseToolkit(AgentRuntime runtime, ExpertManager expertManager,
                                           boolean withMediaTools, ToolCallOrigin origin) {
        Toolkit tk = new Toolkit();
        for (String group : RoutingResult.ALL_TOOL_GROUPS) {
            tk.createToolGroup(group, group, true);
        }
        for (var def : expertManager.getExpertDefs()) {
            var tool = expertManager.getAllTools().stream()
                    .filter(t -> t.getName().equals(def.toolName())).findFirst().orElse(null);
            if (tool != null) {
                tk.registration().agentTool(tool).group(def.groupName()).apply();
            }
        }
        // 自定义/动态智能体统一进常驻 agents 组：各自成组的组名既不在 ALL_TOOL_GROUPS
        // 也不在 ALWAYS_ACTIVE_GROUPS，将永不被路由激活
        tk.createToolGroup("agents", "agents", true);
        for (var tool : expertManager.getAllTools()) {
            if (!tk.getToolNames().contains(tool.getName())) {
                tk.registration().agentTool(tool).group("agents").apply();
            }
        }
        tk.registration().agentTool(runtime.getKnowledgeExpert().getTool()).group("knowledge").apply();
        // 代码工程直接工具（读区间/grep/glob/精确编辑/插入/项目根）进 coding 组，与「编程」子智能体同组，
        // 编程意图路由命中即激活；带本路径来源令牌（写类工具的确认归属须与所在路径一致）
        tk.registration().tool(new com.javaclaw.code.CodeTools(origin)).group("coding").apply();
        // 动态任务工具的能力工具集取自本路径的 expertManager（带本路径来源令牌），
        // 而非 runtime 的交互路径实例——高风险确认归属须与所在路径一致
        tk.registration().tool(new DynamicTaskTool(runtime.getModelFactory(),
                runtime.getMemoryManager(), expertManager.getCapabilityTools())).group("dynamic_task").apply();
        // MCP 工具始终注册：内部对 McpClientManager 是动态引用，热启动新 server 无需重建 toolkit
        tk.registration().tool(new McpTools(runtime.getMcpClientManager())).group("mcp").apply();
        // MCP 配置管理同属 mcp 组：可在对话中新增/更新/启停服务器，成功响应只在 H2 已提交后返回
        tk.registration().tool(new com.javaclaw.mcp.McpManageTools(
                runtime.getMcpClientManager(), origin)).group("mcp").apply();
        // 站点凭据配置属于浏览器能力；与 web_expert 内的 site_save_session 互补：前者登记
        // 账号/元数据，后者保存当前已登录 BrowserContext。路由命中 web 即可直接使用。
        tk.registration().tool(new com.javaclaw.site.SiteCredentialTools(origin)).group("web").apply();
        // 插件工具桥：独立成组，激活时由各路径强制加入（动态工具与路由解耦，避免被路由漏判）
        tk.createToolGroup("plugins", "plugins", true);
        if (!ProjectAccessPolicy.strictIsolationEnabled()) {
            tk.registration().tool(new com.javaclaw.plugin.PluginTools()).group("plugins").apply();
        }
        // 技能三件套：skill 组不在 ALL_TOOL_GROUPS 中（属 ALWAYS_ACTIVE_GROUPS，不参与路由），手动建组
        tk.createToolGroup("skill", "skill", true);
        tk.registration().tool(new com.javaclaw.skill.SkillTools()).group("skill").apply();
        tk.registration().tool(new com.javaclaw.skill.SkillManageTools(origin)).group("skill").apply();
        if (!ProjectAccessPolicy.strictIsolationEnabled()) {
            tk.registration().tool(new com.javaclaw.system.JShellTools(origin)).group("skill").apply();
        }
        tk.registration().tool(new com.javaclaw.task.sdd.run.SddTaskManageTools(origin)).group("task_manage").apply();
        tk.registration().tool(new com.javaclaw.schedule.ScheduleTools(origin)).group("schedule").apply();
        tk.registration().tool(new ExpertManageTools(expertManager)).group("agents").apply();
        // 媒体工具：图片查看（view_image）+ 图片/PDF OCR（ocr_recognize），按路由激活；
        // 仅交互路径注册（见参数 withMediaTools 说明）
        if (withMediaTools) {
            tk.registration().tool(new com.javaclaw.media.MediaTools(runtime.getVisionPreprocessor()))
                    .group("media").apply();
        }
        return tk;
    }

    /**
     * 按路由结果激活工具组，并拼装本轮追加提示词（技能目录 + 技能正文/技能包 + MCP 工具说明
     * + 插件工具清单）。
     *
     * <p>定时任务与循环的每轮装配共用此单一实现——此前两处近逐字复制，新增常驻组
     * 只改一边会让另一路径的提示词与激活组悄悄失配。路由失败或未命中任何组时降级全量。</p>
     *
     * @param toolkit             目标工具集（激活组就地生效）
     * @param routing             路由结果
     * @param runtime             基础设施容器（取 MCP 提示词）
     * @param extraResidentGroups 调用方专属的额外常驻组（如循环的 loop 汇报组），无则不传
     * @return 拼接后的追加系统提示词（追加在基础系统提示词之后）
     */
    public static String activateRoutedGroups(Toolkit toolkit, RoutingResult routing,
                                              AgentRuntime runtime, String... extraResidentGroups) {
        java.util.List<String> groups = (routing.isFallback() || !routing.hasToolGroups())
                ? new java.util.ArrayList<>(RoutingResult.ALL_TOOL_GROUPS)
                : new java.util.ArrayList<>(routing.toolGroups());
        // 常驻组与路由解耦：单一来源见 appendResidentGroups（headless 路径不含 clarify）
        appendResidentGroups(groups, false);
        for (String extra : extraResidentGroups) {
            if (!groups.contains(extra)) groups.add(extra);
        }
        toolkit.setActiveGroups(groups);

        var skillManager = com.javaclaw.skill.SkillManager.getInstance();
        String skillCatalog = skillManager.buildSkillCatalogPrompt(new java.util.HashSet<>(groups));
        String skillsPrompt = (routing.isAllSkills() || routing.isFallback())
                ? skillManager.buildEnabledSkillsPrompt()
                : skillManager.buildFilteredSkillsPrompt(routing.skillNames());
        // 技能包成组注入（包优先，缺失技能跳过）：与聊天路径同一规则——此前 headless 路径
        // 丢弃路由命中的包名，定时/循环任务永远拿不到整包指令
        if (routing.hasBundles()
                && AgentConfig.getInstance().isSkillBundlesEnabled()) {
            StringBuilder bundlePrompts = new StringBuilder();
            for (String bundleName : routing.bundleNames()) {
                bundlePrompts.append(skillManager.buildBundlePrompt(bundleName));
            }
            skillsPrompt = skillsPrompt + bundlePrompts;
        }
        String mcpPrompt = (routing.isAllMcp() || routing.isFallback())
                ? runtime.getMcpClientManager().buildToolsPrompt()
                : runtime.getMcpClientManager().buildFilteredToolsPrompt(routing.mcpServers());
        // 插件工具清单：toolkit 已强制注册 plugins 组（见 buildBaseToolkit），提示词却不注入
        // 的话模型不知道插件工具存在——写明要用某插件工具的定时任务只能盲目探索或静默不用
        String pluginPrompt = com.javaclaw.plugin.PluginManager.getInstance().buildToolsPrompt();
        return skillCatalog + skillsPrompt + mcpPrompt + pluginPrompt;
    }

    /**
     * 向激活组清单追加常驻工具组（与路由解耦，命中与否都可用）——三条路径的<b>单一来源</b>：
     * {@link RoutingResult#ALWAYS_ACTIVE_GROUPS}（clarify / skill / agents）+ 插件桥动态组
     * （plugins，路由器不感知）。此前聊天路径与定时/循环路径各持一份字面量清单，新增常驻组
     * 只改一边会让另一路径「路由命中却无工具可用」，静默缺失只在运行时暴露。
     *
     * @param groups      激活组清单（就地追加、去重）
     * @param interactive 是否交互路径（聊天）：仅交互路径保留 clarify——主动澄清依赖 UI
     *                    回调，headless 路径（定时任务/循环）不注册该工具组，激活空组无意义
     */
    public static void appendResidentGroups(java.util.List<String> groups, boolean interactive) {
        for (String g : RoutingResult.ALWAYS_ACTIVE_GROUPS) {
            if (!interactive && "clarify".equals(g)) continue;
            if (!groups.contains(g)) groups.add(g);
        }
        if (!groups.contains("plugins")) groups.add("plugins");
    }

    /**
     * 构建无头路径（定时任务/循环）的编排器：独立 {@link io.agentscope.core.memory.autocontext.AutoContextMemory}
     * （每次执行互不串记忆）+ 循环检测钩子（无人值守更需防失控）。
     *
     * <p>此前定时任务与循环两处近逐字复制且已经分叉（仅差循环的指纹钩子）——编排器构建
     * 规则（新钩子/模型档位/记忆策略）改一边漏一边只在运行时暴露，与本类收敛 toolkit
     * 装配是同一考量。聊天路径的编排器（跨轮共享记忆、三钩子）语义不同，不并入。</p>
     *
     * @param extraHooks 调用方专属的额外钩子（如循环的工具指纹采集），追加在循环检测之后
     */
    public static ReActAgent buildHeadlessOrchestrator(AgentRuntime runtime, String sysPrompt,
                                                       Toolkit toolkit, Hook... extraHooks) {
        AgentConfig config = AgentConfig.getInstance();
        List<Hook> hooks = new ArrayList<>();
        hooks.add(new LoopDetectionHook());
        Collections.addAll(hooks, extraHooks);
        return ReActAgent.builder()
                .name(AgentConfig.AGENT_NAME)
                .sysPrompt(AgentPrompts.withMandatoryGlobalRules(sysPrompt))
                .model(runtime.getModelFactory().createHighChatModel())
                .toolkit(toolkit)
                .memory(runtime.getModelFactory().defaultAutoContextMemory())
                .modelExecutionConfig(runtime.getModelExecConfig())
                .maxIters(config.getOrchestratorMaxIters())
                .enablePlan()
                .hooks(hooks)
                .build();
    }

    /** 标准流式选项：thinking 开关决定是否订阅 REASONING 事件，各编排路径共用同一构建规则。 */
    public static StreamOptions buildStreamOptions(AgentConfig config) {
        StreamOptions.Builder sb = StreamOptions.builder().incremental(true);
        if (config.isThinkingEnabled()) {
            sb.includeReasoningChunk(true).includeReasoningResult(false)
                    .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.HINT, EventType.AGENT_RESULT);
        } else {
            sb.eventTypes(EventType.TOOL_RESULT, EventType.HINT, EventType.AGENT_RESULT);
        }
        return sb.build();
    }
}
