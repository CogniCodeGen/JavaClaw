package com.javaclaw.infrastructure.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService.Template;
import com.javaclaw.application.mcp.McpTemplatePort;
import com.javaclaw.mcp.McpTemplateLibrary;

import java.util.List;

/** 将内置 MCP 模板目录转换为不可变 Application 值。 */
public final class McpTemplateLibraryAdapter implements McpTemplatePort {
    @Override
    public List<Template> list() {
        return McpTemplateLibrary.ALL.stream().map(template -> new Template(
                template.id(), template.displayName(), template.description(), template.command(),
                template.args(), template.envKeys(), template.toolsHint())).toList();
    }
}
