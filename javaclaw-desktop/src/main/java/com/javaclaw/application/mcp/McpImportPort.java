package com.javaclaw.application.mcp;

import java.util.List;

/** 将外部 MCP JSON 解析为中立配置值的端口。 */
public interface McpImportPort {
    List<McpConfigurationPort.Entry> parse(String json, String fallbackName);
}
