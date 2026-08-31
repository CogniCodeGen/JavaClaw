package com.javaclaw.core.api;

/** 审批触发方式；审批只决定是否授权，实际执行仍受 SandboxPolicy 限制。 */
public enum ApprovalPolicy {
    NEVER,
    ON_REQUEST,
    ON_RISK,
    ALWAYS
}
