package com.javaclaw.server.transport;

import com.javaclaw.agent.automation.AutomationUseCases;
import com.javaclaw.agent.conversation.ProfileUseCases;
import com.javaclaw.agent.knowledge.KnowledgeUseCases;
import com.javaclaw.server.diagnostics.DiagnosticsUseCases;
import com.javaclaw.server.extension.PluginUseCases;
import com.javaclaw.server.extension.mcp.McpUseCases;
import com.javaclaw.server.model.ProviderUseCases;

/**
 * Process-scoped domain use cases consumed only while building RPC handlers.
 *
 * @param profiles Profile 用例；null 表示该端点未装配此能力
 * @param knowledge Knowledge/Memory/Skill 用例；null 表示未装配
 * @param automation Automation/Schedule 用例；null 表示未装配
 * @param providers 云模型配置用例；null 表示未装配
 * @param plugins Plugin 运维用例；null 表示未装配
 * @param diagnostics 诊断查询与导出用例；null 表示未装配
 * @param mcp MCP 配置、发现和授权用例；null 表示未装配
 * @param instructions 文件系统 AGENTS.md 只读解析用例；null 表示未装配
 * @param prompts 提示词预览与无工具优化用例；null 表示未装配
 * @param plans 显式计划采用用例；null 表示未装配
 * @param sites 站点与人工登录用例；null 表示未装配
 * @param networkGrants 准确私网端点授权；null 表示未装配
 * @param toolAuthorizations 有限无人值守 MCP 授权；null 表示未装配
 * @param compaction 有限模型上下文压缩；null 表示未装配
 * @param worktreeRecovery 人工工作树恢复、补丁导出与显式清理；null 表示未装配
 */
public record ServerUseCases(
        ProfileUseCases profiles,
        KnowledgeUseCases knowledge,
        AutomationUseCases automation,
        ProviderUseCases providers,
        PluginUseCases plugins,
        DiagnosticsUseCases diagnostics,
        McpUseCases mcp,
        com.javaclaw.agent.prompt.AgentsInstructionUseCases instructions,
        com.javaclaw.agent.conversation.ProfilePromptUseCases prompts,
        com.javaclaw.agent.conversation.PlanAdoptionUseCases plans,
        com.javaclaw.server.browser.BrowserSiteUseCases sites,
        com.javaclaw.server.network.NetworkGrantUseCases networkGrants,
        com.javaclaw.server.extension.ToolAuthorizationUseCases toolAuthorizations,
        com.javaclaw.agent.conversation.CompactionUseCases compaction,
        com.javaclaw.server.collaboration.WorktreeRecoveryUseCases worktreeRecovery) {
    static final ServerUseCases EMPTY = new ServerUseCases(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    static ServerUseCases profiles(ProfileUseCases profiles) {
        return new ServerUseCases(
                profiles, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
