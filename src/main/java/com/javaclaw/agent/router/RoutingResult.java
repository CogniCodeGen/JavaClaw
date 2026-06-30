package com.javaclaw.agent.router;

import java.util.List;

/**
 * 工具路由结果 — 描述本轮对话需要激活的工具组、技能、技能包和 MCP 服务器
 *
 * @param toolGroups  需要激活的工具组名列表（如 coding, web, email 等）
 * @param skillNames  需要注入的技能名列表
 * @param bundleNames 需要成组注入的技能包名列表（包优先：命中包时包内技能整体注入）
 * @param mcpServers  需要注入的 MCP 服务器名列表（"all" 表示全部）
 * @author JavaClaw
 */
public record RoutingResult(
        List<String> toolGroups,
        List<String> skillNames,
        List<String> bundleNames,
        List<String> mcpServers
) {

    /** 兼容构造：无技能包路由 */
    public RoutingResult(List<String> toolGroups, List<String> skillNames, List<String> mcpServers) {
        this(toolGroups, skillNames, List.of(), mcpServers);
    }

    /** 所有可用的工具组名 */
    public static final List<String> ALL_TOOL_GROUPS = List.of(
            "coding", "knowledge", "web", "email",
            "system", "desktop", "notification", "evaluator", "dynamic_task", "command", "mcp",
            "task_manage", "schedule", "media",
            "clarify"
    );

    /**
     * 不论路由结果如何，始终强制激活的工具组：
     * <ul>
     *   <li>{@code clarify} — 澄清中断工具：任何场景都允许模型主动询问用户；</li>
     *   <li>{@code skill} — 技能按需读取（skill_read）与自管理（skill_create/patch/edit/...）工具：
     *       L0 技能目录恒在系统提示词中，模型随时可能调用 {@code skill_read} 拉取技能正文，
     *       或在经验沉淀时调用 skill_manage 系列工具，故该组不参与路由、始终可用。</li>
     * </ul>
     * 其中 {@code skill} 不在 {@link #ALL_TOOL_GROUPS} 中（路由器不对其路由），由
     * {@code rebuildOrchestratorForTurn} 在每轮统一追加激活。
     */
    public static final List<String> ALWAYS_ACTIVE_GROUPS = List.of("clarify", "skill", "agents");

    /**
     * 路由失败时的降级结果：全部激活
     */
    public static RoutingResult fallbackAll() {
        return new RoutingResult(ALL_TOOL_GROUPS, List.of("all"), List.of(), List.of("all"));
    }

    /**
     * 无工具路由（简单问答，编排器直接回答）
     */
    public static RoutingResult noTools() {
        return new RoutingResult(List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 是否需要激活任何工具组
     */
    public boolean hasToolGroups() {
        return toolGroups != null && !toolGroups.isEmpty();
    }

    /**
     * 是否为全量降级结果
     */
    public boolean isFallback() {
        return toolGroups != null && toolGroups.containsAll(ALL_TOOL_GROUPS);
    }

    /**
     * 技能是否使用全量注入
     */
    public boolean isAllSkills() {
        return skillNames != null && skillNames.contains("all");
    }

    /** 是否命中了技能包 */
    public boolean hasBundles() {
        return bundleNames != null && !bundleNames.isEmpty();
    }

    /**
     * MCP 是否使用全量注入
     */
    public boolean isAllMcp() {
        return mcpServers != null && mcpServers.contains("all");
    }
}
