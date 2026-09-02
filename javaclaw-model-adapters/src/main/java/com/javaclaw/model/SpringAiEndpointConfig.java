package com.javaclaw.model;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Spring AI 模型端点配置。
 *
 * @param endpointId JavaClaw 内稳定端点标识
 * @param provider Provider 类型
 * @param model Provider 原生模型名
 * @param baseUri 可选兼容端点地址；官方默认地址时为空
 * @param timeout 单次网络调用超时
 * @param maximumRetries Provider 客户端最大重试次数
 */
public record SpringAiEndpointConfig(
        String endpointId,
        SpringAiProvider provider,
        String model,
        Optional<URI> baseUri,
        Duration timeout,
        int maximumRetries) {
    /** 校验端点配置。 */
    public SpringAiEndpointConfig {
        endpointId = text(endpointId, "endpointId");
        Objects.requireNonNull(provider, "provider");
        model = text(model, "model");
        baseUri = Objects.requireNonNull(baseUri, "baseUri");
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maximumRetries < 0 || maximumRetries > 10) {
            throw new IllegalArgumentException("maximumRetries must be between 0 and 10");
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
