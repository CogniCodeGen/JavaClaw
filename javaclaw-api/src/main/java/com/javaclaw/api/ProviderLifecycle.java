package com.javaclaw.api;

/** Provider 配置生命周期。 */
public enum ProviderLifecycle {
    /** 可供新 Profile 和 Turn 使用。 */
    ACTIVE,
    /** 暂停新调用，但保留配置和历史引用。 */
    DISABLED,
    /** 已归档，不再允许新 Profile 引用。 */
    ARCHIVED
}
