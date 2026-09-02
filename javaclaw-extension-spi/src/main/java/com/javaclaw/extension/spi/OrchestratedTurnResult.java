package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;

/**
 * 编排器可持久化的 Turn 结果。
 *
 * @param threadId 实际 Thread
 * @param turnId Turn
 * @param status 终态
 * @param output 规范化摘要
 */
public record OrchestratedTurnResult(ThreadId threadId, TurnId turnId, TurnStatus status, CanonicalPayload output) {
    /** 校验结果字段。 */
    public OrchestratedTurnResult {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(output, "output");
        if (status != TurnStatus.COMPLETED && status != TurnStatus.CANCELLED && status != TurnStatus.FAILED) {
            throw new IllegalArgumentException("orchestrated Turn result must be terminal");
        }
    }
}
