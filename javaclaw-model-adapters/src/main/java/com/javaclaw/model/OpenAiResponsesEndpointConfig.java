package com.javaclaw.model;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * OpenAI Responses 端点配置。
 *
 * @param endpointId JavaClaw 内稳定端点标识
 * @param model Responses API 原生模型名
 * @param baseUri 可选 OpenAI-compatible 地址
 * @param timeout 单次网络调用超时
 * @param maximumRetries 官方客户端最大重试次数
 * @param summaryStyle reasoning summary 粒度
 */
public record OpenAiResponsesEndpointConfig(
        String endpointId,
        String model,
        Optional<URI> baseUri,
        Duration timeout,
        int maximumRetries,
        ReasoningSummaryStyle summaryStyle) {
    /** 校验端点配置。 */
    public OpenAiResponsesEndpointConfig {
        endpointId = text(endpointId, "endpointId");
        model = text(model, "model");
        baseUri = Objects.requireNonNull(baseUri, "baseUri");
        timeout = Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(summaryStyle, "summaryStyle");
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
