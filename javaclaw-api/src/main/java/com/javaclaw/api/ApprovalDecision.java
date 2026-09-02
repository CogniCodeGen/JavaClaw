package com.javaclaw.api;

/** 人工审批结果。 */
public enum ApprovalDecision {
    /** 本次调用获准。 */
    APPROVED,
    /** 用户明确拒绝。 */
    DENIED
}
