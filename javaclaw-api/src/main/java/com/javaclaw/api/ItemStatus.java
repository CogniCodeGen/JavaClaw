package com.javaclaw.api;

/** Item 的持久生命周期状态。 */
public enum ItemStatus {
    /** 内容正在流式生成。 */
    IN_PROGRESS,
    /** 内容已完整提交。 */
    COMPLETED,
    /** 内容生成被取消。 */
    CANCELLED,
    /** 内容生成失败。 */
    FAILED
}
