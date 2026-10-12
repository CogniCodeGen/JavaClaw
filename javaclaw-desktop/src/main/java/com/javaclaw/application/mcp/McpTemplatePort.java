package com.javaclaw.application.mcp;

import java.util.List;

/** MCP 内置模板目录端口。 */
public interface McpTemplatePort {
    List<McpManagementApplicationService.Template> list();
}
