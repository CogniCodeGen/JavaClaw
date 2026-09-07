package com.javaclaw.api;

/** 执行配置字段的来源层次。 */
public enum ConfigurationSource {
    /** 来自 INSTALLATION 层。 */
    INSTALLATION,
    /** 来自 WORKSPACE 层。 */
    WORKSPACE,
    /** 来自 THREAD 层。 */
    THREAD,
    /** 来自 TURN 层。 */
    TURN,
    /** 来自 ROLE 层。 */
    ROLE,
    /** 来自 PARENT 层。 */
    PARENT,
    /** 来自 SYSTEM 层。 */
    SYSTEM
}
