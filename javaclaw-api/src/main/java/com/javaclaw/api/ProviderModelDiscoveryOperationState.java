package com.javaclaw.api;

/** Provider 模型目录发现临时操作的状态。 */
public enum ProviderModelDiscoveryOperationState {
    /** 网络调用仍在执行。 */
    RUNNING,
    /** 目录已成功读取。 */
    SUCCEEDED,
    /** 目录读取失败。 */
    FAILED,
    /** 调用方、页面或连接已经取消操作。 */
    CANCELLED
}
