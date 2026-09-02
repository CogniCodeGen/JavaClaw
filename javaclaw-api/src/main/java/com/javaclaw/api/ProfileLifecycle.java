package com.javaclaw.api;

/** Agent Profile 生命周期。 */
public enum ProfileLifecycle {
    /** 可供 Workspace、Thread 和新 Turn 选择。 */
    ACTIVE,
    /** 暂停新 Turn，但保留历史引用。 */
    DISABLED,
    /** 已归档，只允许读取历史版本。 */
    ARCHIVED
}
