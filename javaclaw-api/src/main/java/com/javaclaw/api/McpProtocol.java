package com.javaclaw.api;

/** JavaClaw 5.0 唯一支持的 MCP 协议版本。 */
public final class McpProtocol {
    /** MCP wire 协议日期；客户端和服务端不得协商为其他版本。 */
    public static final String VERSION = "2026-07-28";

    private McpProtocol() {}
}
