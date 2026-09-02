package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider 的非计费本地检查结果。
 *
 * @param provider Provider 精确版本
 * @param readiness 就绪状态
 * @param capabilities 适配器声明能力
 * @param detail 已脱敏的人类可读说明
 * @param checkedAt 检查时间
 */
public record ProviderStatus(
        ProviderRef provider,
        ProviderReadiness readiness,
        ProviderCapabilities capabilities,
        Optional<String> detail,
        Instant checkedAt) {
    /** 校验状态。 */
    public ProviderStatus {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(capabilities, "capabilities");
        detail = Objects.requireNonNull(detail, "detail").map(String::strip).filter(value -> !value.isEmpty());
        Objects.requireNonNull(checkedAt, "checkedAt");
    }
}
