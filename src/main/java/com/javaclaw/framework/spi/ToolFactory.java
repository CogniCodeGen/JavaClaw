package com.javaclaw.framework.spi;

@FunctionalInterface
public interface ToolFactory {
    FrameworkTool create(ToolContext context);
}
