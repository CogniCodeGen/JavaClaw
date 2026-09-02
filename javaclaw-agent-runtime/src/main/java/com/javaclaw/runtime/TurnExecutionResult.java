package com.javaclaw.runtime;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;

/**
 * 单 Turn 终态。
 *
 * @param turnId Turn
 * @param status COMPLETED、CANCELLED 或 FAILED
 * @param assistantText 已提交的可见文本
 * @param usage 累计 usage
 * @param toolCalls 已执行或恢复的工具调用数
 * @param providerState 最终 opaque state
 * @param errorCode 失败代码
 */
public record TurnExecutionResult(
        TurnId turnId,
        TurnStatus status,
        String assistantText,
        ModelUsage usage,
        int toolCalls,
        Optional<ProviderState> providerState,
        Optional<String> errorCode) {
    /** 校验终态结果。 */
    public TurnExecutionResult {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(status, "status");
        if (status != TurnStatus.COMPLETED && status != TurnStatus.CANCELLED && status != TurnStatus.FAILED) {
            throw new IllegalArgumentException("result status must be terminal");
        }
        assistantText = Objects.requireNonNull(assistantText, "assistantText");
        Objects.requireNonNull(usage, "usage");
        if (toolCalls < 0) {
            throw new IllegalArgumentException("toolCalls must not be negative");
        }
        providerState = Objects.requireNonNull(providerState, "providerState");
        errorCode = Objects.requireNonNull(errorCode, "errorCode");
        if ((status == TurnStatus.FAILED) != errorCode.isPresent()) {
            throw new IllegalArgumentException("errorCode presence must match FAILED status");
        }
    }
}
