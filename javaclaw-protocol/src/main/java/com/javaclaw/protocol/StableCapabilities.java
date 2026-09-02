package com.javaclaw.protocol;

import java.util.LinkedHashSet;
import java.util.Set;

/** Protocol v2 客户端与 App Server 共同声明的 5.x 稳定能力目录。 */
public final class StableCapabilities {
    /** 固定 MCP Host 协议能力。 */
    public static final String MCP_2026_07_28 = "extension.mcp-2026-07-28";

    private static final Set<String> BASE = Set.of(
            "core.input-request",
            "core.item-envelope",
            "core.rollout-hash-chain",
            "core.secret-vault",
            "core.security-grants",
            "core.typed-settings",
            "extension.job-supervisor",
            "extension.view-schema-v2");
    private static final Set<String> WITH_MCP = with(BASE, MCP_2026_07_28);

    private StableCapabilities() {}

    /**
     * 返回不依赖可选运行时的 5.x 稳定能力。
     *
     * <p>MCP 等需要真实 Broker/Sandbox 端口的能力不在本集合中，避免 App Server 在 fail-closed 端口下向客户端伪报可用。
     *
     * @return 不可变基础能力集合
     */
    public static Set<String> all() {
        return BASE;
    }

    /**
     * 返回包含真实 MCP Host 的能力集合。
     *
     * @return 不可变能力集合
     */
    public static Set<String> withMcp() {
        return WITH_MCP;
    }

    private static Set<String> with(Set<String> base, String capability) {
        LinkedHashSet<String> capabilities = new LinkedHashSet<>(base);
        capabilities.add(capability);
        return Set.copyOf(capabilities);
    }
}
