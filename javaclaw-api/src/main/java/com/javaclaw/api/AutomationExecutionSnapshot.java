package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * 平台在自动化 Execution 启动时生成的权威执行快照。
 *
 * <p>快照固定 Profile、Provider、PermissionProfile、单 Turn 预算与工具目录。客户端不能构造或提交该对象；后续 Turn 只能收窄权限，并且必须继续使用这里冻结的工具身份与 Schema。
 *
 * @param profile 精确 Agent Profile
 * @param provider 精确 Provider 与模型
 * @param permissionProfile 精确 PermissionProfile
 * @param turnBudget 每个子 Turn 的冻结预算上限
 * @param toolCatalog 平台权威冻结的工具目录
 * @param unattendedExecutionScope Schedule 无人值守来源；交互或普通自动化为空
 */
public record AutomationExecutionSnapshot(
        AgentProfileRef profile,
        ProviderRef provider,
        PermissionProfileRef permissionProfile,
        TurnBudget turnBudget,
        ToolCatalogSnapshot toolCatalog,
        Optional<UnattendedExecutionScope> unattendedExecutionScope) {
    /** 校验完整快照。 */
    public AutomationExecutionSnapshot {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(permissionProfile, "permissionProfile");
        Objects.requireNonNull(turnBudget, "turnBudget");
        Objects.requireNonNull(toolCatalog, "toolCatalog");
        unattendedExecutionScope = Objects.requireNonNull(unattendedExecutionScope, "unattendedExecutionScope");
    }

    /**
     * 为 Schedule Occurrence 绑定平台创建的无人值守来源。
     *
     * @param scope 精确 Schedule 与 Occurrence 身份
     * @return 保留原冻结配置的新快照
     */
    public AutomationExecutionSnapshot withUnattendedExecutionScope(UnattendedExecutionScope scope) {
        UnattendedExecutionScope checked = Objects.requireNonNull(scope, "scope");
        if (unattendedExecutionScope.isPresent()
                && !unattendedExecutionScope.orElseThrow().equals(checked)) {
            throw new IllegalStateException("automation snapshot already belongs to another Schedule occurrence");
        }
        return new AutomationExecutionSnapshot(
                profile, provider, permissionProfile, turnBudget, toolCatalog, Optional.of(checked));
    }
}
