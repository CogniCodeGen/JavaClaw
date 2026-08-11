package com.javaclaw.agent;

import io.agentscope.core.tool.Toolkit;

import java.util.Objects;

/** Registers either one annotated tool object or every component of a composite tool set. */
public final class ToolRegistrationSupport {

    private ToolRegistrationSupport() {}

    public static void register(Toolkit toolkit, Object tools) {
        Objects.requireNonNull(toolkit, "toolkit");
        if (tools instanceof ToolObjectProvider provider) {
            provider.toolObjects().forEach(toolkit::registerTool);
        } else {
            toolkit.registerTool(tools);
        }
    }

    public static void register(Toolkit toolkit, Object tools, String group) {
        Objects.requireNonNull(toolkit, "toolkit");
        if (tools instanceof ToolObjectProvider provider) {
            provider.toolObjects()
                    .forEach(
                            component ->
                                    toolkit.registration().tool(component).group(group).apply());
        } else {
            toolkit.registration().tool(tools).group(group).apply();
        }
    }
}
