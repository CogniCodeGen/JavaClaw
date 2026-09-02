package com.javaclaw.extension.spi;

/** 扩展是否允许由管理员停用。 */
public enum ExtensionAvailability {
    /** 平台启动所必需，管理端只能查看。 */
    REQUIRED,
    /** 可独立停用；停用后必须实时拒绝新的贡献调用。 */
    OPTIONAL
}
