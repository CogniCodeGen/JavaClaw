package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 安装、Workspace 和 Thread 解析后的完整执行默认值。
 *
 * @param role 精确角色版本
 * @param provider 精确 Provider 与模型
 * @param permissionProfile 精确权限配置
 * @param approvalPolicy 最低审批要求
 * @param budget 单 Turn 有限预算
 * @param visibleCapabilities 可向模型公开的能力上限
 * @param reasoning 推理偏好，继承 Provider 默认时为空
 */
public record ExecutionDefaults(
        AgentRoleRef role,
        ProviderRef provider,
        PermissionProfileRef permissionProfile,
        ApprovalPolicy approvalPolicy,
        TurnBudget budget,
        Set<String> visibleCapabilities,
        Optional<ReasoningPreference> reasoning) {
    /** 校验完整默认值并冻结集合。 */
    public ExecutionDefaults {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(permissionProfile, "permissionProfile");
        Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        Objects.requireNonNull(budget, "budget");
        visibleCapabilities = Set.copyOf(Objects.requireNonNull(visibleCapabilities, "visibleCapabilities"));
        reasoning = Objects.requireNonNull(reasoning, "reasoning");
    }

    /** @return 所有字段均显式提供的配置覆盖 */
    public ExecutionOverrides overrides() {
        return new ExecutionOverrides(
                Optional.of(role),
                Optional.of(provider),
                Optional.of(permissionProfile),
                Optional.of(approvalPolicy),
                Optional.of(budget),
                Optional.of(visibleCapabilities),
                reasoning);
    }
}
