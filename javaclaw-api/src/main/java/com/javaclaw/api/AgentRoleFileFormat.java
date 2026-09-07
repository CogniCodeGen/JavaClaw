package com.javaclaw.api;

/** Agent Role 文件的明确交换模式。 */
public enum AgentRoleFileFormat {
    /** 只导出公开兼容核心字段。 */
    CODEX_PORTABLE,
    /** 保留 JavaClaw 版本化字段和扩展命名空间。 */
    JAVACLAW_LOSSLESS
}
