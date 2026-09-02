package com.javaclaw.protocol;

import java.util.Objects;

/**
 * JSON-RPC 调用标识；v2 统一使用非空字符串，避免数字精度差异。
 *
 * @param value 调用方生成的唯一字符串
 */
public record RpcId(String value) {
    /** 校验调用标识。 */
    public RpcId {
        value = Objects.requireNonNull(value, "value").strip();
        if (value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException("rpc id length must be between 1 and 128");
        }
    }
}
