package com.javaclaw.extension.spi;

/** 扩展执行信任层。 */
public enum ExtensionTrust {
    /** 随发行版签名并进程内装配。 */
    BUILT_IN,
    /** 只能由 Sandbox Supervisor 在进程外运行。 */
    THIRD_PARTY
}
