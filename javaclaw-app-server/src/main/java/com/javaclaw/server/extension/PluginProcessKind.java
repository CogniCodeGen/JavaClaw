package com.javaclaw.server.extension;

/** Every executable contribution is hosted outside the App Server JVM. */
public enum PluginProcessKind {
    MCP_SERVER,
    PRE_TOOL_HOOK,
    POST_TOOL_HOOK,
    SERVICE
}
