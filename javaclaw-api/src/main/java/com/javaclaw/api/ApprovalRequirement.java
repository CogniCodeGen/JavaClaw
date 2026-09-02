package com.javaclaw.api;

/** 工具调用需要人工确认的最低强度，声明顺序从宽松到严格。 */
public enum ApprovalRequirement {
    /** 策略允许时无需逐次确认。 */
    NONE,
    /** Workspace 写入及更高风险需要确认。 */
    RISKY,
    /** 每次调用都需要确认。 */
    EVERY_CALL
}
