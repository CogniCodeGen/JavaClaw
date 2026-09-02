package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 显式计费 Provider round-trip 的脱敏结果。
 *
 * <p>该类型不包含 Prompt、模型响应正文、Secret 或厂商原始异常。UNKNOWN_OUTCOME 表示平台不能证明外部调用是否完成，客户端不得自动重试。
 *
 * @param provider 精确 Provider 与模型版本
 * @param state 验证终态
 * @param latencyMillis 端到端延迟毫秒；UNKNOWN_OUTCOME 时为 0
 * @param usage 已知 token 使用量；外部结果不确定时为空
 * @param capabilities 本次路由声明的能力
 * @param errorCode 脱敏稳定失败码；成功时为空
 * @param completedAt 结果确定或被判定未知的时间
 */
public record ProviderVerificationResult(
        ProviderRef provider,
        ProviderVerificationState state,
        long latencyMillis,
        Optional<ProviderVerificationUsage> usage,
        ProviderCapabilities capabilities,
        Optional<String> errorCode,
        Instant completedAt) {
    /** 校验结果不会用缺失字段伪装成功。 */
    public ProviderVerificationResult {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(state, "state");
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("latencyMillis must not be negative");
        }
        usage = Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(capabilities, "capabilities");
        errorCode = Objects.requireNonNull(errorCode, "errorCode")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        Objects.requireNonNull(completedAt, "completedAt");
        if ((state == ProviderVerificationState.SUCCEEDED) == errorCode.isPresent()) {
            throw new IllegalArgumentException("errorCode must be absent only for SUCCEEDED");
        }
        if (state == ProviderVerificationState.SUCCEEDED && usage.isEmpty()) {
            throw new IllegalArgumentException("SUCCEEDED verification requires usage");
        }
        if (state == ProviderVerificationState.UNKNOWN_OUTCOME && latencyMillis != 0) {
            throw new IllegalArgumentException("UNKNOWN_OUTCOME latency must be zero");
        }
    }
}
