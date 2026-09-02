package com.javaclaw.api;

/** Managed Worktree 生成的内容寻址 Artifact 类型。 */
public enum ManagedWorktreeArtifactKind {
    /** 供用户检查或离线保存的完整 Git Patch。 */
    PATCH,
    /** cleanup 前强制生成并验证的恢复 Backup。 */
    BACKUP
}
