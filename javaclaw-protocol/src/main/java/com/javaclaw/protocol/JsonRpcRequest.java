package com.javaclaw.protocol;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;

/**
 * JSON-RPC request。
 *
 * @param id 调用标识
 * @param method Protocol v2 方法名
 * @param params 对象参数
 */
public record JsonRpcRequest(RpcId id, String method, CanonicalPayload params) implements JsonRpcMessage {
    /** 校验 request。 */
    public JsonRpcRequest {
        Objects.requireNonNull(id, "id");
        method = RpcNames.require(method);
        Objects.requireNonNull(params, "params");
    }
}
