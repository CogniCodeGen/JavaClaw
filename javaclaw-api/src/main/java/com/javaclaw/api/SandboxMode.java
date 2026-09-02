package com.javaclaw.api;

/** Sandbox 的执行模式。 */
public enum SandboxMode {
    /** 无交互、捕获完整输出。 */
    BATCH,
    /** 使用受控 PTY 的交互会话。 */
    PTY
}
