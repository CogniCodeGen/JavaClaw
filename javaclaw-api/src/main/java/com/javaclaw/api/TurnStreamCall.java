package com.javaclaw.api;

import java.util.Objects;

/**
 * 一次持久模型意图的展示身份，重放时保持不变。
 *
 * @param invocationNumber Turn 内从 1 开始的调用编号
 * @param attemptId 不透明尝试标识，不可空
 * @param messageItemId 预留的最终助手 Item ID，不可空
 */
public record TurnStreamCall(int invocationNumber, String attemptId, ItemId messageItemId) {
    /** 校验调用身份。 */
    public TurnStreamCall {
        if (invocationNumber < 1) {
            throw new IllegalArgumentException("invocationNumber must be positive");
        }
        attemptId = Objects.requireNonNull(attemptId, "attemptId");
        if (attemptId.isBlank() || attemptId.length() > 100) {
            throw new IllegalArgumentException("invalid attemptId");
        }
        Objects.requireNonNull(messageItemId, "messageItemId");
    }
}
