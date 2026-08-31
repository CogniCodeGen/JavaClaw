package com.javaclaw.server.extension.mcp;

import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.sandbox.api.SandboxPolicy;

/** Creates transport instances without exposing MCP wire types to Agent Runtime. */
@FunctionalInterface
public interface McpTransportFactory {
    /** 为指定配置和 Turn 权限打开独立传输；返回值由客户端拥有并关闭，无法精确隔离时失败关闭。 */
    McpTransport open(McpConfiguration configuration, TurnExecutionContext turn, SandboxPolicy authority)
            throws Exception;
}
