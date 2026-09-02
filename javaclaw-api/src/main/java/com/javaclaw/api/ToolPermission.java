package com.javaclaw.api;

import java.util.Set;

/**
 * 工具目录的权限上限。
 *
 * @param allowedTools 允许的完整工具名称；空集合表示不允许工具
 * @param maximumRisk 允许的最高风险等级
 * @param approvalRequirement 人工审批强度
 */
public record ToolPermission(Set<String> allowedTools, ToolRisk maximumRisk, ApprovalRequirement approvalRequirement) {
    /** 复制名称并校验策略。 */
    public ToolPermission {
        allowedTools = allowedTools.stream()
                .map(value -> Preconditions.text(value, "tool"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (maximumRisk == null || approvalRequirement == null) {
            throw new IllegalArgumentException("tool risk and approval requirement are required");
        }
    }
}
