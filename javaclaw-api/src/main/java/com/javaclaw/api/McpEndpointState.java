package com.javaclaw.api;

/** MCP 端点实时开关。 */
public enum McpEndpointState {
    /** 允许发现和发起新调用。 */
    ENABLED,
    /** 立即阻止发现和新调用，但保留配置与目录历史。 */
    DISABLED
}
