package com.javaclaw.api;

/** 显式计费 Provider round-trip 的脱敏终态。 */
public enum ProviderVerificationState {
    /** 模型端点完成最小无工具调用。 */
    SUCCEEDED,
    /** 模型调用或受控 Harness 失败。 */
    FAILED,
    /** 达到平台固定验证时限。 */
    TIMED_OUT,
    /** 调用方在安全检查点取消验证。 */
    CANCELLED,
    /** 服务在模型调用后未能持久化终态，禁止自动重试。 */
    UNKNOWN_OUTCOME
}
