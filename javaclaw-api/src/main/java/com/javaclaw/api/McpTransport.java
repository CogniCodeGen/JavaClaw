package com.javaclaw.api;

/** MCP 端点受信传输来源。 */
public enum McpTransport {
    /** 用户创建的 HTTPS Streamable HTTP 端点。 */
    STREAMABLE_HTTPS,
    /** 仅由已验证签名 Bundle 注册的进程外 stdio 端点。 */
    SIGNED_BUNDLE_STDIO
}
