package com.javaclaw.desktop.settings;

/** 强类型设置页统一的异步读取与写入状态。 */
public enum SettingsLoadState {
    /** 尚未读取。 */
    INITIAL,
    /** 正在读取目录。 */
    LOADING,
    /** 权威状态已读取。 */
    READY,
    /** 正在提交写操作。 */
    SAVING,
    /** 最近一次读取或写入失败。 */
    ERROR
}
