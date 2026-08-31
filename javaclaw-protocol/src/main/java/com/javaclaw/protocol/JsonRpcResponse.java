package com.javaclaw.protocol;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON-RPC 响应；result 与 error 必须恰好提供一个。
 *
 * @param jsonrpc 固定字符串 2.0，不能省略
 * @param id 请求 id；无法识别请求时可为 JSON null 节点，Java 引用不可为 null
 * @param result 成功结果节点；失败时为 Java null
 * @param error 失败对象；成功时为 Java null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(String jsonrpc, JsonNode id, JsonNode result, JsonRpcError error)
        implements JsonRpcFrame {
    /** 校验响应 id 和互斥结果，防止客户端将含糊响应误判为成功。 */
    public JsonRpcResponse {
        if (!"2.0".equals(jsonrpc)) {
            throw new IllegalArgumentException("jsonrpc must be 2.0");
        }
        id = Objects.requireNonNull(id, "id");
        if (!id.isNull() && !id.isTextual() && !id.isIntegralNumber()) {
            throw new IllegalArgumentException("id must be null, a string, or an integer");
        }
        if ((result == null) == (error == null)) {
            throw new IllegalArgumentException("response requires exactly one of result or error");
        }
    }

    /** 创建带指定 id 的成功响应；JSON null 结果需使用 NullNode，不接受 Java null。 */
    public static JsonRpcResponse success(JsonNode id, JsonNode result) {
        return new JsonRpcResponse("2.0", id, Objects.requireNonNull(result, "result"), null);
    }

    /** 创建结构化错误响应；data 可为空，message/data 应预先脱敏。 */
    public static JsonRpcResponse failure(JsonNode id, int code, String message, JsonNode data) {
        return new JsonRpcResponse("2.0", id, null, new JsonRpcError(code, message, data));
    }
}
