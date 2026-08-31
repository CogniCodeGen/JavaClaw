package com.javaclaw.server.collaboration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.javaclaw.core.api.ThreadId;

/** Durable managed-worktree lifecycle used for recovery and explicit cleanup. */
public interface WorktreeRepository {
    /** 列出仍需恢复或清理的受控 worktree；不自动删除冲突现场。 */
    List<WorktreeRecord> listRequiringCleanup();

    /** 按更新时间倒序查询工作区最近的工作树，包含已清理记录以便找回备份；limit 为 1–256。 */
    List<WorktreeRecord> listByWorkspace(String workspaceId, int limit);

    /** 按子 Thread 查找受控 worktree；不存在时返回 Optional.empty。 */
    Optional<WorktreeRecord> findByChild(ThreadId childThreadId);

    /** 登记 worktree 路径、父子关联和合成基线；调用方负责先完成安全快照。 */
    WorktreeRecord create(WorktreeDraft draft);

    /** 按 expectedRevision 更新工作树状态与清理标记；保留冲突/恢复说明，版本冲突拒绝覆盖。 */
    WorktreeRecord setState(String id, State state, boolean cleanupRequired, String details, long expectedRevision);

    /** 读取已完成清理的稳定结果；相同 key 的不同参数必须拒绝。 */
    Optional<WorktreeRecord> replayCleanup(WorktreeRecoveryUseCases.CleanupRequest request);

    /** 读取尚未提交最终结果的清理意图，恢复时复用删除前生成的备份，而不是再次读取可能已删除的目录。 */
    Optional<WorktreeRecord> pendingCleanup(WorktreeRecoveryUseCases.CleanupRequest request);

    /** 原子校验版本并保存清理意图；备份 SHA 在文件删除前持久化。 重连时返回原意图，不重新扩大权限；调用方仍需持有 Runtime 目录维护租约。 */
    WorktreeRecord beginCleanup(WorktreeRecoveryUseCases.CleanupRequest request, String backupSha256);

    /** 原子保存最终状态和幂等结果；Git 在事务外执行，失败保留 ABANDONED 及备份引用。 */
    WorktreeRecord finishCleanup(WorktreeRecoveryUseCases.CleanupRequest request, boolean cleaned);

    /** 工作树生命周期；CONFLICT/ABANDONED 需要保留现场，CLEANED 表示已完成受控清理。 */
    enum State {
        ACTIVE,
        MERGED,
        CONFLICT,
        ABANDONED,
        CLEANED
    }

    /**
     * 受控 worktree 的持久生命周期投影，用于崩溃恢复和显式清理。
     *
     * @param id 资源或声明的稳定标识
     * @param workspaceId 所属 Workspace 标识
     * @param parentThreadId 父 Thread 标识
     * @param childThreadId 独立子 Thread 标识
     * @param path 平台缓存下的受控 worktree 路径，不是用户真实工作目录
     * @param baselineCommit 受控快照生成的合成基线 commit，不修改用户真实 index
     * @param state 持久生命周期状态
     * @param cleanupRequired 是否仍需显式恢复或清理 worktree
     * @param details 恢复或冲突说明；不得携带凭据
     * @param revision 持久修订号，用于乐观锁和缓存失效
     * @param createdAt 创建时间
     * @param updatedAt 最近更新时间；尚未配置的资源可为 null
     */
    record WorktreeRecord(
            String id,
            String workspaceId,
            ThreadId parentThreadId,
            ThreadId childThreadId,
            Path path,
            String baselineCommit,
            State state,
            boolean cleanupRequired,
            String details,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * 已准备的工作树登记信息；持久层分配版本和状态。
     *
     * @param id 资源或声明的稳定标识
     * @param workspaceId 所属 Workspace 标识
     * @param parentThreadId 父 Thread 标识
     * @param childThreadId 独立子 Thread 标识
     * @param path 平台缓存下的受控 worktree 路径，不是用户真实工作目录
     * @param baselineCommit 受控快照生成的合成基线 commit，不修改用户真实 index
     */
    record WorktreeDraft(
            String id,
            String workspaceId,
            ThreadId parentThreadId,
            ThreadId childThreadId,
            Path path,
            String baselineCommit) {}
}
