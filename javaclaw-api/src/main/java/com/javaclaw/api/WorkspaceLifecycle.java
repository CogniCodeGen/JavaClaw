package com.javaclaw.api;

/** Workspace 登记的生命周期；归档不会触碰用户目录。 */
public enum WorkspaceLifecycle {
    /** 可创建新 Thread 和 Turn。 */
    ACTIVE,
    /** 只保留历史读取，不允许启动新的工作。 */
    ARCHIVED
}
