package com.javaclaw.server.extension.mcp;

import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Stateless JSON-RPC codec for MCP 2026-07-28; no legacy initialize/session state exists. */
public final class McpCodec {
    private final ObjectMapper json;
    private final ObjectNode clientCapabilities;

    /** 绑定受控 mapper 并建立固定协议版本的客户端能力声明；不实现旧版初始化会话。 */
    public McpCodec(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
        clientCapabilities = json.createObjectNode();
        clientCapabilities.putObject("elicitation").putObject("form");
        clientCapabilities.putObject("roots");
        clientCapabilities.putObject("sampling");
        clientCapabilities.putObject("extensions").putObject(McpProtocol.TASKS_EXTENSION);
    }

    /** 创建带随机 id、协议版本及客户端能力 _meta 的请求；parameters 必须为对象或 null，输入节点会复制。 */
    public ObjectNode request(String method, JsonNode parameters) {
        String normalized = Objects.requireNonNull(method, "method").strip();
        if (!normalized.matches("[A-Za-z0-9._/-]{1,200}")) {
            throw new IllegalArgumentException("invalid MCP method");
        }
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", "mcp_" + UUID.randomUUID().toString().replace("-", ""));
        request.put("method", normalized);
        ObjectNode params;
        if (parameters == null || parameters.isNull()) {
            params = json.createObjectNode();
        } else if (parameters.isObject()) {
            params = ((ObjectNode) parameters).deepCopy();
        } else {
            throw new IllegalArgumentException("MCP params must be an object");
        }
        ObjectNode meta;
        JsonNode existing = params.get("_meta");
        if (existing == null || existing.isNull()) {
            meta = params.putObject("_meta");
        } else if (existing.isObject()) {
            meta = (ObjectNode) existing;
        } else {
            throw new IllegalArgumentException("MCP params._meta must be an object");
        }
        meta.put(McpProtocol.PROTOCOL_VERSION_META, McpProtocol.VERSION);
        ObjectNode info = meta.putObject(McpProtocol.CLIENT_INFO_META);
        info.put("name", "JavaClaw");
        info.put("version", "4.0.0");
        meta.set(McpProtocol.CLIENT_CAPABILITIES_META, clientCapabilities.deepCopy());
        request.set("params", params);
        return request;
    }

    /** 创建 notifications/cancelled 通知；requestId 必须为字符串或整数，reason 可为空。 */
    public ObjectNode cancelled(JsonNode requestId, String reason) {
        if (requestId == null || requestId.isNull() || (!requestId.isTextual() && !requestId.isIntegralNumber())) {
            throw new IllegalArgumentException("MCP request id is invalid");
        }
        ObjectNode notification = json.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", McpProtocol.CANCELLED);
        ObjectNode params = notification.putObject("params");
        params.set("requestId", requestId.deepCopy());
        if (reason != null && !reason.isBlank()) {
            params.put("reason", reason.strip());
        }
        return notification;
    }

    /** 核对响应 JSON-RPC 和 request id，解析最终结果并复制未知字段；错误响应转换为 McpProtocolException。 */
    public JsonNode result(ObjectNode request, JsonNode response) throws McpProtocolException {
        if (response == null
                || !response.isObject()
                || !"2.0".equals(response.path("jsonrpc").asText())) {
            throw new McpProtocolException(-32603, "invalid MCP JSON-RPC response", response);
        }
        if (!Objects.equals(canonical(request.get("id")), canonical(response.get("id")))) {
            throw new McpProtocolException(-32603, "MCP response id mismatch", response);
        }
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            throw new McpProtocolException(
                    error.path("code").asInt(-32603),
                    error.path("message").asText("MCP request failed"),
                    error.get("data"));
        }
        JsonNode result = response.get("result");
        if (result == null) {
            throw new McpProtocolException(-32603, "MCP response has no result", response);
        }
        return result.deepCopy();
    }

    /**
     * 编码单个 MCP JSON 对象；换行或 SSE 分帧由 transport 管理。
     *
     * @throws com.fasterxml.jackson.core.JsonProcessingException 无法编码
     */
    public String encode(JsonNode value) throws JsonProcessingException {
        return json.writeValueAsString(value);
    }

    private String canonical(JsonNode value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }
}
