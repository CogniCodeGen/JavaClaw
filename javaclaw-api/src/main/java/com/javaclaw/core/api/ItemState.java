package com.javaclaw.core.api;

/** 持久 Item 生命周期；STARTED 无最终内容，COMPLETED/FAILED 保存最终可恢复内容。 */
public enum ItemState {
    STARTED,
    COMPLETED,
    FAILED
}
