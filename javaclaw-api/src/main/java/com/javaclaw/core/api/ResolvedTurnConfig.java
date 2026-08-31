package com.javaclaw.core.api;

import java.util.Objects;

/**
 * Immutable profile and security snapshot captured before a Turn is persisted.
 *
 * @param profile 非空、版本固定的 Profile 快照
 * @param turnConfig 服务端完成权限收窄后的非空执行配置
 */
public record ResolvedTurnConfig(ExecutionProfile profile, TurnConfig turnConfig) {
    /** 要求 Profile 与最终配置均存在；此类型只封装解析结果，不重新解析权限。 */
    public ResolvedTurnConfig {
        profile = Objects.requireNonNull(profile, "profile");
        turnConfig = Objects.requireNonNull(turnConfig, "turnConfig");
    }
}
