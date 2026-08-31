package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * 工作树恢复摘要；parentThreadId/childThreadId 关联对话，revision 用于乐观锁，running 仅供展示。 backupSha256 可为空；无本地文件路径或内部 Git 输出，updatedAt
 * 为持久更新时间。
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
public record WorktreeInfo(
        String id,
        String workspaceId,
        String parentThreadId,
        String childThreadId,
        String state,
        long revision,
        boolean running,
        String backupSha256,
        String details,
        Instant updatedAt) {}
