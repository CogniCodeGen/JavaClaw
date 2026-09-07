package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.AgentRoleRef;

/**
 * 自动化定义可选择的权威 Agent Role 条目。
 *
 * @param role 精确且仍可用于新 Turn 的 Role 引用
 * @param displayName 用户可见名称
 */
public record AutomationRoleOption(AgentRoleRef role, String displayName) {
    /** 校验精确引用和显示名称。 */
    public AutomationRoleOption {
        Objects.requireNonNull(role, "role");
        displayName = Objects.requireNonNull(displayName, "displayName").strip();
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }
}
