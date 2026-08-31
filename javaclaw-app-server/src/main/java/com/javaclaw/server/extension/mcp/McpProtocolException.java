package com.javaclaw.server.extension.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/** Protocol-level failure with forward-preserved MCP error data. */
public final class McpProtocolException extends Exception {
    private final int code;
    private final JsonNode data;

    /** 保存错误代码并复制可选 MCP data，空错误说明使用默认文本；跨公共边界前仍需脱敏。 */
    public McpProtocolException(int code, String message, JsonNode data) {
        super(message == null || message.isBlank() ? "MCP request failed" : message);
        this.code = code;
        this.data = data == null ? null : data.deepCopy();
    }

    /** 返回 MCP 错误码，供调用方区分 input_required、版本错误与普通失败。 */
    public int code() {
        return code;
    }

    /** 返回错误 data 的深拷贝；没有详情时为 null，不允许直接写入诊断或客户端消息。 */
    public JsonNode data() {
        return data == null ? null : data.deepCopy();
    }
}
