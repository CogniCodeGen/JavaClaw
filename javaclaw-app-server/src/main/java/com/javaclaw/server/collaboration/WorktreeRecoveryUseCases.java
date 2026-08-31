package com.javaclaw.server.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.javaclaw.agent.collaboration.CollaborationGateway;
import com.javaclaw.core.api.ThreadId;
import com.javaclaw.core.api.WorkspaceId;

/** 人工恢复工作树的窄端口；不提供绕过父 Turn 审批的补丁应用入口。 */
public interface WorktreeRecoveryUseCases {
    /** 列出工作区最近 256 个工作树及清理后的备份记录；仅返回非敏感摘要，不执行 Git。 */
    List<RecoveryInfo> listRecovery(WorkspaceId workspaceId);

    /** 为已退出的子任务生成相对合成基线的有界补丁附件；版本变化拒绝导出过期选择。 */
    CollaborationGateway.PatchResult exportPatch(ThreadId childThreadId, long expectedRevision);

    /** 显式清理工作树；未合并结果需要 discardUnmerged，运行中目录禁止清理，重复 key 不重复执行。 */
    RecoveryInfo cleanup(CleanupRequest request);

    /** 删除父/子 Thread 前确认没有仍需恢复的工作树，避免丢失目录所有权和恢复引用。 */
    void assertCanDeleteThread(ThreadId threadId);

    /**
     * 恢复视图；标识、状态与修订为持久值，running 仅是当前快照，不替代清理时的原子租约检查。 backupSha256 为冲突/清理前的补丁附件，可为空；details 仅提供固定状态说明，不包含 Git 原始输出。
     *
     * @param id 工作树稳定标识
     * @param workspaceId 所属工作区标识
     * @param parentThreadId 父任务标识
     * @param childThreadId 子任务标识
     * @param state 持久恢复状态
     * @param revision 正数修订号
     * @param running 子任务是否仍有活动执行，仅为瞬时提示
     * @param backupSha256 备份附件哈希；没有备份时为空
     * @param details 非敏感恢复说明
     * @param updatedAt 持久更新时间
     */
    record RecoveryInfo(
            String id,
            String workspaceId,
            ThreadId parentThreadId,
            ThreadId childThreadId,
            String state,
            long revision,
            boolean running,
            String backupSha256,
            String details,
            Instant updatedAt) {}

    /**
     * 用户确认的清理请求；expectedRevision 为正数，key 绑定全部参数且不超过 500 字符。 不接收任何文件路径，目标仅来自 H2 的受控工作树记录。
     *
     * @param childThreadId 受控子任务标识
     * @param expectedRevision 用户确认的正数版本
     * @param discardUnmerged 是否明确允许丢弃未合并文件
     * @param key 绑定全部参数的非空幂等键，不超过 500 字符
     */
    record CleanupRequest(ThreadId childThreadId, long expectedRevision, boolean discardUnmerged, String key) {
        /** 校验清理目标与幂等约束；空 key 或无效版本立即拒绝。 */
        public CleanupRequest {
            Objects.requireNonNull(childThreadId, "childThreadId");
            if (expectedRevision < 1 || key == null || key.isBlank() || key.length() > 500) {
                throw new IllegalArgumentException("positive revision and bounded idempotencyKey are required");
            }
            key = key.strip();
        }
    }
}
