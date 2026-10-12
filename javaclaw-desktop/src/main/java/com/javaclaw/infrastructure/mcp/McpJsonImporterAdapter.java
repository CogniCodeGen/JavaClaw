package com.javaclaw.infrastructure.mcp;

import com.javaclaw.application.mcp.McpConfigurationPort;
import com.javaclaw.application.mcp.McpImportPort;
import com.javaclaw.mcp.McpJsonImporter;

import java.util.List;

/** 复用经过兼容性验证的 MCP JSON 解析器。 */
public final class McpJsonImporterAdapter implements McpImportPort {

    private final McpJsonImporter importer;

    public McpJsonImporterAdapter(McpJsonImporter importer) {
        this.importer = java.util.Objects.requireNonNull(importer, "importer");
    }

    @Override
    public List<McpConfigurationPort.Entry> parse(String json, String fallbackName) {
        return importer.parse(json, fallbackName).stream()
                .map(McpConfigManagerAdapter::toEntry).toList();
    }
}
