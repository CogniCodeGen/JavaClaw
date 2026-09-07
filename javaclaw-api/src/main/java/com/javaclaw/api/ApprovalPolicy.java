package com.javaclaw.api;

/** 执行配置的最低审批强度；声明顺序从宽松到严格。 */
public enum ApprovalPolicy {
    /** 在权限策略允许时无需逐次审批。 */
    NONE,
    /** 写入及更高风险需要审批。 */
    RISKY,
    /** 所有工具调用均需审批。 */
    EVERY_CALL;

    /** @return 对应的工具权限审批要求 */
    public ApprovalRequirement requirement() {
        return ApprovalRequirement.valueOf(name());
    }

    /**
     * 保留两个来源中更严格的审批要求。
     *
     * @param other 另一安全边界
     * @return 不低于任一输入的审批要求
     */
    public ApprovalPolicy intersect(ApprovalPolicy other) {
        java.util.Objects.requireNonNull(other, "other");
        return ordinal() >= other.ordinal() ? this : other;
    }
}
