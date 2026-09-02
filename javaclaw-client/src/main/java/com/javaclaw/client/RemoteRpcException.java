package com.javaclaw.client;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.JsonRpcError;

/** App Server 返回的结构化 JSON-RPC 错误。 */
public final class RemoteRpcException extends RuntimeException {
    private final int code;
    private final Optional<CanonicalPayload> data;

    /**
     * 创建远端错误。
     *
     * @param error JSON-RPC error
     */
    public RemoteRpcException(JsonRpcError error) {
        super(Objects.requireNonNull(error, "error").message());
        code = error.code();
        data = error.data();
    }

    /**
     * 返回稳定错误码。
     *
     * @return ProtocolErrorCode 中的值
     */
    public int code() {
        return code;
    }

    /**
     * 返回可选结构化详情。
     *
     * @return 原始规范 JSON
     */
    public Optional<CanonicalPayload> data() {
        return data;
    }
}
