package com.javaclaw.protocol;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;

/**
 * 不要求响应的 JSON-RPC notification。
 *
 * @param method Protocol v3 方法名
 * @param params 对象参数
 */
public record JsonRpcNotification(String method, CanonicalPayload params) implements JsonRpcMessage {
    /** 校验 notification。 */
    public JsonRpcNotification {
        method = RpcNames.require(method);
        Objects.requireNonNull(params, "params");
    }
}
