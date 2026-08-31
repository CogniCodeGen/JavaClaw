package com.javaclaw.protocol;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 需要关联响应的 JSON-RPC 请求；id 只接受字符串或整数。
 *
 * @param jsonrpc 固定字符串 2.0，不能省略
 * @param id 字符串或整数 JSON 节点，非空
 * @param method 非空白 JSON-RPC 方法名
 * @param params 对象、数组或 JSON null 节点；Java 引用不可为 null
 */
public record JsonRpcRequest(String jsonrpc, JsonNode id, String method, JsonNode params) implements JsonRpcFrame {
    /** 校验 JSON-RPC 版本、方法名和参数形状；请求 id 额外遵守请求关联约束。 */
    public JsonRpcRequest {
        if (!"2.0".equals(jsonrpc)) {
            throw new IllegalArgumentException("jsonrpc must be 2.0");
        }
        id = Objects.requireNonNull(id, "id");
        if (!id.isTextual() && !id.isIntegralNumber()) {
            throw new IllegalArgumentException("id must be a string or integer");
        }
        method = requireMethod(method);
        params = requireParams(params);
    }

    static String requireMethod(String method) {
        method = Objects.requireNonNull(method, "method").strip();
        if (method.isEmpty()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        return method;
    }

    static JsonNode requireParams(JsonNode params) {
        params = Objects.requireNonNull(params, "params");
        if (!params.isNull() && !params.isObject() && !params.isArray()) {
            throw new IllegalArgumentException("params must be an object, array, or null");
        }
        return params;
    }
}
