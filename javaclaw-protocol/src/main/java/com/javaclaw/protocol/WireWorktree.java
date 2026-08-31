package com.javaclaw.protocol;

/**
 * 工作树恢复协议投影；不返回服务端目录或原始 Git 输出。
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
 * @param updatedAt ISO-8601 更新时间
 */
public record WireWorktree(
        String id,
        String workspaceId,
        String parentThreadId,
        String childThreadId,
        String state,
        long revision,
        boolean running,
        String backupSha256,
        String details,
        String updatedAt) {}
