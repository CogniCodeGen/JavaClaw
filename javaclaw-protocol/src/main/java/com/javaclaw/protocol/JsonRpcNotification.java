package com.javaclaw.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 无需响应的 JSON-RPC 通知；没有请求 id。
 *
 * @param jsonrpc 固定字符串 2.0，不能省略
 * @param method 非空白 JSON-RPC 方法名
 * @param params 对象、数组或 JSON null 节点；Java 引用不可为 null
 */
public record JsonRpcNotification(String jsonrpc, String method, JsonNode params) implements JsonRpcFrame {
    /** 校验 JSON-RPC 版本、方法名和参数形状；请求 id 额外遵守请求关联约束。 */
    public JsonRpcNotification {
        if (!"2.0".equals(jsonrpc)) {
            throw new IllegalArgumentException("jsonrpc must be 2.0");
        }
        method = JsonRpcRequest.requireMethod(method);
        params = JsonRpcRequest.requireParams(params);
    }
}
