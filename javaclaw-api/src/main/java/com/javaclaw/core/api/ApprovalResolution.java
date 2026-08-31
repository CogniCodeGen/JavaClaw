package com.javaclaw.core.api;

import java.time.Instant;
import java.util.Objects;

/**
 * 绑定到具体 Thread/Turn 的审批决议，供恢复和审计使用。
 *
 * @param approvalId 非空白审批请求标识
 * @param threadId 所属 Thread 的非空标识
 * @param turnId 所属 Turn 的非空标识
 * @param approved 是否同意本次审批；不等同于取消沙箱约束
 * @param resolvedAt 决议时间，非空
 */
public record ApprovalResolution(
        String approvalId, ThreadId threadId, TurnId turnId, boolean approved, Instant resolvedAt) {
    /** 校验审批关联标识和决议时间；approved 本身不会扩大沙箱权限。 */
    public ApprovalResolution {
        approvalId = ThreadId.required(approvalId, "approvalId");
        threadId = Objects.requireNonNull(threadId, "threadId");
        turnId = Objects.requireNonNull(turnId, "turnId");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    }
}
