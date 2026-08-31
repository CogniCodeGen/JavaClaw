package com.javaclaw.sdk;

/**
 * MCP 连接或授权状态变更的公开通知。
 *
 * @param mcpId MCP Server 配置标识
 * @param state 服务端生命周期状态
 * @param revision 资源修订号，更新时用作 expectedRevision
 */
public record McpStatusChangedNotification(String mcpId, String state, long revision) implements ClientNotification {}
