package com.javaclaw.server.extension;

import java.util.Objects;

/**
 * Provenance metadata only; it never grants runtime permissions.
 *
 * @param algorithm 固定 Ed25519 算法名称
 * @param keyId 信任公钥标识，不是凭据值
 * @param value 非空白签名编码，不是私钥
 */
public record PluginSignature(String algorithm, String keyId, String value) {
    /** 校验签名字段并拒绝 Ed25519 之外的算法；验签仍由独立验证器执行。 */
    public PluginSignature {
        algorithm = required(algorithm, "algorithm");
        keyId = required(keyId, "keyId");
        value = required(value, "value");
        if (!"Ed25519".equals(algorithm)) {
            throw new IllegalArgumentException("only Ed25519 plugin signatures are supported");
        }
    }

    private static String required(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
