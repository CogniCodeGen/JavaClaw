package com.javaclaw.agent.tool;

/** 工具来源分类，用于来源策略和可审计显示，不根据签名自动提升权限。 */
public enum ToolOrigin {
    BUILTIN,
    MCP,
    PLUGIN
}
