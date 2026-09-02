package com.javaclaw.api;

/** Turn 用户输入请求的持久生命周期。 */
public enum InputRequestState {
    /** 等待客户端提交输入。 */
    PENDING,
    /** 客户端已提交通过校验的输入。 */
    RESOLVED,
    /** 请求超过绝对有效期。 */
    EXPIRED,
    /** 所属 Turn 已取消或平台主动终止等待。 */
    CANCELLED
}
