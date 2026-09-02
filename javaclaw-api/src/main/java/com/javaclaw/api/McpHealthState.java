package com.javaclaw.api;

/** MCP 端点健康检查结果。 */
public enum McpHealthState {
    /** 尚未执行健康检查。 */
    UNKNOWN,
    /** 固定协议、认证与基本能力检查通过。 */
    HEALTHY,
    /** 缺少或需要更新认证。 */
    AUTH_REQUIRED,
    /** 远端不可达或响应无效。 */
    UNAVAILABLE,
    /** 远端不支持 JavaClaw 固定的协议版本。 */
    PROTOCOL_MISMATCH
}
