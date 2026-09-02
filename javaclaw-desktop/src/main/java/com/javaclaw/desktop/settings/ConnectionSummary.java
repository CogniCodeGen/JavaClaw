package com.javaclaw.desktop.settings;

import java.util.Set;

/**
 * 当前本地 SDK 会话可安全展示的连接摘要。
 *
 * @param serverName App Server 名称
 * @param serverVersion App Server 版本
 * @param protocolVersion App Protocol 版本
 * @param stableCapabilities 已协商 stable capabilities
 * @param experimentalCapabilities 已协商 experimental capabilities
 */
public record ConnectionSummary(
        String serverName,
        String serverVersion,
        int protocolVersion,
        Set<String> stableCapabilities,
        Set<String> experimentalCapabilities) {
    /** 复制能力集合。 */
    public ConnectionSummary {
        serverName = java.util.Objects.requireNonNull(serverName, "serverName");
        serverVersion = java.util.Objects.requireNonNull(serverVersion, "serverVersion");
        stableCapabilities = Set.copyOf(stableCapabilities);
        experimentalCapabilities = Set.copyOf(experimentalCapabilities);
    }
}
