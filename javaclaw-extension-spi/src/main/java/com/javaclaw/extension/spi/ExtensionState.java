package com.javaclaw.extension.spi;

/** 扩展生命周期状态。 */
public enum ExtensionState {
    /** 已安装但未启用。 */
    INSTALLED,
    /** 正在启动或健康检查。 */
    STARTING,
    /** 可接受新调用。 */
    ENABLED,
    /** 已禁用，不接受新调用。 */
    DISABLED,
    /** 健康检查失败后隔离。 */
    QUARANTINED,
    /** 正在从注册表移除。 */
    REMOVING
}
