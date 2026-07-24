package com.javaclaw.workflow.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.agent.router.RoutingResult;
import io.agentscope.core.tool.Toolkit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 自定义工作流节点共享的本地工具组白名单与激活策略。 */
final class WorkflowToolGroupPolicy {
    static final List<String> KNOWN_TOOL_GROUPS;
    static final Set<String> ALLOWED_TOOL_GROUPS;

    static {
        LinkedHashSet<String> known = new LinkedHashSet<>(RoutingResult.ALL_TOOL_GROUPS);
        known.addAll(List.of("agents", "plugins", "skill"));
        KNOWN_TOOL_GROUPS = List.copyOf(known);
        known.remove("mcp");
        ALLOWED_TOOL_GROUPS = Set.copyOf(known);
    }

    private WorkflowToolGroupPolicy() { }

    static void validate(JsonNode groupsNode, List<String> errors) {
        if (!groupsNode.isArray()) return;
        for (JsonNode group : groupsNode) {
            String name = group.asText();
            if ("mcp".equals(name)) {
                errors.add("自定义工作流默认不允许远程 MCP 工具组");
            } else if (!ALLOWED_TOOL_GROUPS.contains(name)) {
                errors.add("未知工具组: " + name);
            }
        }
    }

    static List<String> read(JsonNode groupsNode) {
        if (groupsNode == null || groupsNode.isMissingNode()) return List.of();
        if (!groupsNode.isArray()) throw new IllegalArgumentException("toolGroups 必须是数组");
        List<String> groups = new ArrayList<>();
        groupsNode.forEach(item -> groups.add(item.asText()));
        if (groups.stream().anyMatch(group -> !ALLOWED_TOOL_GROUPS.contains(group))) {
            throw new SecurityException("自定义工作流包含未授权或未知工具组");
        }
        return List.copyOf(groups);
    }

    /**
     * Agent 节点的空声明表示不开放工具；Tool 节点的空声明仍允许其显式命名的本地工具。
     * 两者都会物理移除 MCP 桥，非空声明则只激活白名单中的指定组。
     */
    static void restrict(Toolkit toolkit, List<String> groups, boolean allowAllLocalWhenEmpty) {
        if (!allowAllLocalWhenEmpty || !groups.isEmpty()) {
            toolkit.updateToolGroups(KNOWN_TOOL_GROUPS, false);
        }
        toolkit.removeToolGroups(List.of("mcp"));
        if (!groups.isEmpty()) toolkit.updateToolGroups(groups, true);
    }
}
