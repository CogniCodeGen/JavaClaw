package com.javaclaw.core.api;

import java.time.Instant;
import java.util.Objects;

/**
 * 用户输入交互的最终决议，取消也作为显式结果保存。
 *
 * @param requestId 非空白用户输入请求标识
 * @param threadId 所属 Thread 的非空标识
 * @param turnId 所属 Turn 的非空标识
 * @param value 回答文本；null 归一为空字符串，cancelled 为 true 时仅保留审计语义
 * @param cancelled 是否取消输入；取消时不应将 value 当作有效回答
 * @param resolvedAt 决议时间，非空
 */
public record UserInputResolution(
        String requestId, ThreadId threadId, TurnId turnId, String value, boolean cancelled, Instant resolvedAt) {
    /** 校验交互归属和决议时间，将缺失回答归一为空字符串。 */
    public UserInputResolution {
        requestId = ThreadId.required(requestId, "requestId");
        threadId = Objects.requireNonNull(threadId, "threadId");
        turnId = Objects.requireNonNull(turnId, "turnId");
        value = value == null ? "" : value;
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    }
}
