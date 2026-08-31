package com.javaclaw.server.extension.mcp;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Transport boundary shared by sandboxed stdio and brokered Streamable HTTP. */
public interface McpTransport extends AutoCloseable {
    /**
     * 执行有界、可取消的一次请求；通知通过 notifications 回调，timeout 包含等待响应，不接受无界 SSE。
     *
     * @throws Exception 传输失败、取消、超时或响应超过预算
     */
    JsonNode exchange(
            ObjectNode request,
            Map<String, String> transportHeaders,
            Duration timeout,
            Consumer<JsonNode> notifications)
            throws Exception;

    /** 取消关联请求；stdio 发送取消通知，HTTP 关闭对应 Exchange，不通过扩大网络权限重试。 */
    void cancel(JsonNode requestId, String reason);

    @Override
    void close();
}
