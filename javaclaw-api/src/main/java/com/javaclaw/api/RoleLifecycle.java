package com.javaclaw.api;

/** Agent Role 生命周期。 */
public enum RoleLifecycle {
    /** 允许开始新工作。 */
    ACTIVE,
    /** 暂停新工作，保留历史。 */
    DISABLED,
    /** 归档后只允许历史读取。 */
    ARCHIVED
}
