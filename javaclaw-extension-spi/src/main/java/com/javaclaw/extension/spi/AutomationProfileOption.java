package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.AgentProfileRef;

/**
 * 自动化定义可选择的权威 Agent Profile 条目。
 *
 * @param profile 精确且仍可用于新 Turn 的 Profile 引用
 * @param displayName 用户可见名称
 */
public record AutomationProfileOption(AgentProfileRef profile, String displayName) {
    /** 校验精确引用和显示名称。 */
    public AutomationProfileOption {
        Objects.requireNonNull(profile, "profile");
        displayName = Objects.requireNonNull(displayName, "displayName").strip();
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }
}
