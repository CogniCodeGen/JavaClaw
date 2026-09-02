package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 可查询、可乐观锁更新的审批快照。
 *
 * @param request 不含原始工具参数的申请摘要
 * @param state 当前状态
 * @param revision 审批资源版本，从 1 开始
 * @param resolutionReason 终态原因；等待中为空
 * @param updatedAt 最近状态更新时间
 */
public record ApprovalRecord(
        ApprovalRequest request,
        ApprovalState state,
        long revision,
        Optional<String> resolutionReason,
        Instant updatedAt) {
    /** 校验审批生命周期不变量。 */
    public ApprovalRecord {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(state, "state");
        revision = Preconditions.positive(revision, "revision");
        resolutionReason = Objects.requireNonNull(resolutionReason, "resolutionReason")
                .map(value -> Preconditions.text(value, "resolutionReason"));
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(request.createdAt())) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
        if ((state == ApprovalState.PENDING) != resolutionReason.isEmpty()) {
            throw new IllegalArgumentException("only pending approval may omit resolutionReason");
        }
    }

    /**
     * 判断是否仍可由客户端决议。
     *
     * @return 等待中为 true
     */
    public boolean pending() {
        return state == ApprovalState.PENDING;
    }
}
