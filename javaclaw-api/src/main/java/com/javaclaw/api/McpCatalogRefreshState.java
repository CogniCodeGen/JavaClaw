package com.javaclaw.api;

/** MCP Catalog 刷新的持久状态。 */
public enum McpCatalogRefreshState {
    /** 正在从固定协议端点分页发现。 */
    RUNNING,
    /** 新 Catalog revision 已原子提交。 */
    COMPLETED,
    /** 发现或提交失败，旧 Catalog 继续生效。 */
    FAILED
}
