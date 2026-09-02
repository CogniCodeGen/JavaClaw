package com.javaclaw.api;

/** MCP Catalog 条目类型。 */
public enum McpCatalogKind {
    /** 可治理执行的 Tool。 */
    TOOL,
    /** 仅作为外部数据读取的 Prompt。 */
    PROMPT,
    /** 仅作为外部数据读取的 Resource。 */
    RESOURCE
}
