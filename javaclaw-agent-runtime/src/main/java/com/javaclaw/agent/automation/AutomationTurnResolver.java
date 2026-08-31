package com.javaclaw.agent.automation;

import com.javaclaw.core.api.ProfileKind;
import com.javaclaw.core.api.ResolvedTurnConfig;
import com.javaclaw.core.api.Workspace;

/** App-server-owned adapter from a versioned Profile to an immutable Turn snapshot. */
@FunctionalInterface
public interface AutomationTurnResolver {
    /** 在指定 Workspace 解析 Profile 并核对 requiredKind，返回固定配置；不得允许无人值守 Profile 扩大权限。 */
    ResolvedTurnConfig resolve(String profileId, Workspace workspace, ProfileKind requiredKind);
}
