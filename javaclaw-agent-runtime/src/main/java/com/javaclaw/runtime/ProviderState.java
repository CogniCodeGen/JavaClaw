package com.javaclaw.runtime;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;

/**
 * Harness 不解析的 Provider conversation state。
 *
 * @param providerId Provider 标识
 * @param format Provider 私有格式版本
 * @param payload 规范 JSON wrapper
 */
public record ProviderState(String providerId, String format, CanonicalPayload payload) {
    /** 校验状态身份。 */
    public ProviderState {
        providerId = text(providerId, "providerId");
        format = text(format, "format");
        Objects.requireNonNull(payload, "payload");
    }

    /**
     * 返回状态内容摘要。
     *
     * @return SHA-256 十六进制
     */
    public String digest() {
        return payload.sha256();
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
