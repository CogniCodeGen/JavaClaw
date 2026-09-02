package com.javaclaw.api;

/** 父子 Thread 绑定的受管 Git Worktree 生命周期。 */
public enum ManagedWorktreeState {
    /** 已创建且尚未开始执行。 */
    READY,
    /** 子 Thread 正在执行，禁止 backup 与 cleanup。 */
    RUNNING,
    /** 子 Thread 已由管理端中断。 */
    INTERRUPTED,
    /** 子 Thread 已完成，可以导出或备份 Patch。 */
    COMPLETED,
    /** 子 Thread 或应用 Patch 时发现冲突，禁止 cleanup。 */
    CONFLICTED,
    /** 子 Thread 执行失败，仍可备份现场。 */
    FAILED,
    /** 父 Turn 正在应用 Patch。 */
    APPLYING,
    /** Patch 已由父 Turn 完整应用。 */
    APPLIED,
    /** 应用副作用结果无法可靠判定，禁止自动重试。 */
    UNKNOWN_OUTCOME,
    /** 已验证 Backup，正在删除受管目录；崩溃后只允许继续 cleanup。 */
    CLEANING,
    /** 已验证 Backup 后清理受管目录。 */
    CLEANED
}
