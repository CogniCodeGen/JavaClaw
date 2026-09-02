package com.javaclaw.api;

/** 无人值守调用账本的终态；任何终态都不会自动重放同一 invocation。 */
public enum UnattendedInvocationOutcome {
    /** 工具已明确成功。 */
    SUCCEEDED,
    /** 工具在确认没有未知副作用后明确失败。 */
    FAILED,
    /** 外部副作用结果未知；额度已消费且禁止自动重试。 */
    UNKNOWN_OUTCOME
}
