package com.javaclaw.framework.spi;

import java.util.List;

/** Creates a complete, run-scoped tool set (for host, MCP or dynamically installed tools). */
@FunctionalInterface
public interface ToolProviderFactory {
    List<FrameworkTool> create(ToolContext context);
}
