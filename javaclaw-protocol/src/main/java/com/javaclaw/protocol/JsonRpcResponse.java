package com.javaclaw.protocol;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;

/**
 * JSON-RPC response，result 与 error 必须且只能存在一个。
 *
 * @param id 对应 request
 * @param result 成功结果
 * @param error 错误结果
 */
public record JsonRpcResponse(RpcId id, Optional<CanonicalPayload> result, Optional<JsonRpcError> error)
        implements JsonRpcMessage {
    /** 校验互斥结果。 */
    public JsonRpcResponse {
        Objects.requireNonNull(id, "id");
        result = Objects.requireNonNull(result, "result");
        error = Objects.requireNonNull(error, "error");
        if (result.isPresent() == error.isPresent()) {
            throw new IllegalArgumentException("exactly one of result and error is required");
        }
    }

    /**
     * 创建成功响应。
     *
     * @param id 调用标识
     * @param result 结果
     * @return 成功响应
     */
    public static JsonRpcResponse success(RpcId id, CanonicalPayload result) {
        return new JsonRpcResponse(id, Optional.of(result), Optional.empty());
    }

    /**
     * 创建错误响应。
     *
     * @param id 调用标识
     * @param error 错误
     * @return 错误响应
     */
    public static JsonRpcResponse failure(RpcId id, JsonRpcError error) {
        return new JsonRpcResponse(id, Optional.empty(), Optional.of(error));
    }
}
