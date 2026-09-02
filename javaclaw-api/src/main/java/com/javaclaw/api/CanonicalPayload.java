package com.javaclaw.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 已由协议层规范化的 JSON 对象；API 不依赖具体 JSON 库。
 *
 * @param json UTF-8 JSON 对象文本，键顺序与数字表示已经规范化
 */
public record CanonicalPayload(String json) {
    /** 校验 payload 是非空 JSON 对象。 */
    public CanonicalPayload {
        json = Preconditions.text(json, "json");
        if (!json.startsWith("{") || !json.endsWith("}")) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
    }

    /**
     * 计算规范文本的 SHA-256，用于 Rollout 与 EffectReceipt 校验。
     *
     * @return 小写十六进制摘要
     */
    public String sha256() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
