package com.javaclaw.api;

/** MCP 外部数据的远端缓存作用域。 */
public enum McpCacheScope {
    /** 结果只能在当前授权上下文中复用。 */
    PRIVATE,
    /** 结果不含授权上下文专属数据，可跨授权上下文复用。 */
    PUBLIC
}
