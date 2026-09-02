package com.javaclaw.protocol;

import java.util.Objects;

/**
 * initialize 成功结果。
 *
 * @param appProtocolVersion 服务端协议版本
 * @param serverName 服务端名称
 * @param serverVersion 服务端版本
 * @param capabilities 已协商能力
 * @param secretKey 当前连接用于 SealedSecret 的 X25519 公钥
 */
public record InitializeResult(
        int appProtocolVersion,
        String serverName,
        String serverVersion,
        NegotiatedCapabilities capabilities,
        SessionKeyInfo secretKey) {
    /** 校验服务端信息。 */
    public InitializeResult {
        if (appProtocolVersion != ProtocolVersion.CURRENT) {
            throw new IllegalArgumentException("server protocol version must be current");
        }
        serverName = text(serverName, "serverName");
        serverVersion = text(serverVersion, "serverVersion");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(secretKey, "secretKey");
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
