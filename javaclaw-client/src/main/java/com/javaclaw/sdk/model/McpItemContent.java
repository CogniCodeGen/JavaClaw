package com.javaclaw.sdk.model;

/**
 * 外部 MCP 调用结果，不将服务端正文提升为系统指令。
 *
 * @param server 连接标识
 * @param tool 工具标识
 * @param status 实际完成或错误状态
 * @param output 有界输出正文
 * @param document 完整原始 JSON，保留未知扩展
 */
public record McpItemContent(String server, String tool, String status, String output, JsonDocument document)
        implements ItemContent {
    @Override
    public String kind() {
        return "mcpToolCall";
    }
}
