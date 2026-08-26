package com.javaclaw.framework.api;

/** Public request contract for model exposure and execution-time exact tool authorization. */
public final class ToolNameAccess {
    public static final String ATTRIBUTE = "framework.allowedTools";

    private ToolNameAccess() { }

    public static boolean allows(RunRequest request, String toolName) {
        return ToolAccessPolicy.from(request).allowsTool(toolName);
    }
}
