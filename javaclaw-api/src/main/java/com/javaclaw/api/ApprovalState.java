package com.javaclaw.api;

/** 审批请求的持久生命周期。 */
public enum ApprovalState {
    /** 等待客户端决议。 */
    PENDING,
    /** 客户端已允许本次调用。 */
    APPROVED,
    /** 客户端已拒绝本次调用。 */
    DENIED,
    /** 等待超过有效期。 */
    EXPIRED,
    /** 审批后实时权限被撤销。 */
    REVOKED,
    /** 所属 Turn 在等待期间取消。 */
    CANCELLED
}
