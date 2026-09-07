package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * 自动化 Execution 启动时由 App Server 冻结的权威执行快照。
 *
 * <p>恢复及后续 Turn 继续使用同一配置和工具目录；角色新版本不能改变活动 Execution，实时撤权仍立即生效。
 *
 * @param configuration 完整已解析配置，不可为空
 * @param toolCatalog 平台冻结工具目录，不可为空
 * @param unattendedExecutionScope Schedule 无人值守来源；普通执行时为空
 */
public record AutomationExecutionSnapshot(
        ResolvedTurnConfig configuration,
        ToolCatalogSnapshot toolCatalog,
        Optional<UnattendedExecutionScope> unattendedExecutionScope) {
    /** 校验配置、工具目录摘要与无人值守来源。 */
    public AutomationExecutionSnapshot {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(toolCatalog, "toolCatalog");
        unattendedExecutionScope = Objects.requireNonNull(unattendedExecutionScope, "unattendedExecutionScope");
        if (!configuration.toolCatalogDigest().equals(toolCatalog.digest())) {
            throw new IllegalArgumentException("configuration must reference frozen tool catalog");
        }
    }

    /** @return 精确角色引用 */
    public AgentRoleRef role() {
        return configuration.role();
    }

    /** @return 精确 Provider 和模型 */
    public ProviderRef provider() {
        return configuration.provider();
    }

    /** @return 精确权限配置 */
    public PermissionProfileRef permissionProfile() {
        return configuration.permissionProfile();
    }

    /** @return 每个子 Turn 的冻结预算上限 */
    public TurnBudget turnBudget() {
        return configuration.budget();
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
        return new AutomationExecutionSnapshot(configuration, toolCatalog, Optional.of(checked));
    }
}
