package com.javaclaw.runtime;

/** 模型停止原因。 */
public enum ModelFinishReason {
    /** 正常完成。 */
    COMPLETE,
    /** 请求执行工具。 */
    TOOL_CALLS,
    /** 命中输出 token 上限。 */
    LENGTH,
    /** Provider 安全策略拒绝。 */
    CONTENT_FILTER,
    /** Provider 返回无法映射的原因。 */
    OTHER
}
