package com.javaclaw.api;

/** 服务端可验证的 Thread 文件执行隔离意图；客户端不能指定执行路径。 */
public enum ThreadExecutionIntent {
    /** 根 Thread 直接使用 Workspace 根。 */
    WORKSPACE,
    /** 子 Thread 只读使用 Workspace，不创建 Worktree。 */
    READ_ONLY,
    /** 子 Thread 在平台分配的 Managed Worktree 中写入。 */
    ISOLATED_WRITE
}
