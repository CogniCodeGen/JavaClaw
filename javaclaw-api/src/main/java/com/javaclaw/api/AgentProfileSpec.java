package com.javaclaw.api;

import java.util.Set;

/**
 * Agent Profile 中可由用户修改的完整配置。
 *
 * @param displayName 用户可见名称
 * @param systemInstruction Profile 系统说明；可为空字符串
 * @param provider 精确 Provider 与模型引用
 * @param permissionProfile 精确权限配置引用
 * @param visibleTools 可向模型公开的工具名上限
 * @param budget 默认 Turn 预算
 */
public record AgentProfileSpec(
        String displayName,
        String systemInstruction,
        ProviderRef provider,
        PermissionProfileRef permissionProfile,
        Set<String> visibleTools,
        TurnBudget budget) {
    /** 复制集合并校验配置。 */
    public AgentProfileSpec {
        displayName = Preconditions.text(displayName, "displayName");
        systemInstruction = java.util.Objects.requireNonNull(systemInstruction, "systemInstruction")
                .strip();
        java.util.Objects.requireNonNull(provider, "provider");
        java.util.Objects.requireNonNull(permissionProfile, "permissionProfile");
        visibleTools = visibleTools.stream()
                .map(value -> Preconditions.text(value, "visibleTool"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        java.util.Objects.requireNonNull(budget, "budget");
    }
}
