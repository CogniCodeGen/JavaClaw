package com.javaclaw.protocol;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;

/**
 * JSON-RPC 错误对象。
 *
 * @param code 稳定错误码
 * @param message 简短错误说明
 * @param data 可选结构化详情
 */
public record JsonRpcError(int code, String message, Optional<CanonicalPayload> data) {
    /** 校验错误信息。 */
    public JsonRpcError {
        message = Objects.requireNonNull(message, "message").strip();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        data = Objects.requireNonNull(data, "data");
    }
}
