package com.javaclaw.protocol;

import java.util.Objects;

/**
 * initialize 返回的会话级 Secret 封装公钥。
 *
 * @param algorithm 固定算法套件
 * @param keyId 仅在当前连接有效的 opaque key id
 * @param encodedPublicKey Base64URL 编码的 X.509 X25519 公钥
 */
public record SessionKeyInfo(String algorithm, String keyId, String encodedPublicKey) {
    /** 当前 Protocol v3 唯一支持的算法套件。 */
    public static final String ALGORITHM = "X25519-HKDF-SHA256-AES-256-GCM";

    /** 校验算法、会话标识和编码公钥。 */
    public SessionKeyInfo {
        if (!ALGORITHM.equals(algorithm)) {
            throw new IllegalArgumentException("session secret algorithm is not supported");
        }
        keyId = identifier(keyId, "keyId", 120);
        encodedPublicKey = base64Url(encodedPublicKey, "encodedPublicKey", 256);
    }

    static String identifier(String value, String name, int maxLength) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.length() > maxLength || !checked.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return checked;
    }

    static String base64Url(String value, String name, int maxLength) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty() || checked.length() > maxLength || !checked.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(name + " is not unpadded Base64URL");
        }
        return checked;
    }
}
