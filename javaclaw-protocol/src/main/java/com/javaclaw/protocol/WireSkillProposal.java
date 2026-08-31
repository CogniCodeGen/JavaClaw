package com.javaclaw.protocol;

/**
 * 带来源、目标版本和审阅状态的 Skill 学习提案。
 *
 * @param id 提案标识
 * @param workspaceId 来源工作区
 * @param targetId 建议更新或新建的 Skill 标识
 * @param name 展示名
 * @param version 发布版本
 * @param manifest 声明正文；不是 JVM 扩展
 * @param sourceItemIds 实际成功执行的证据 Item
 * @param expectedTargetRevision 固定的目标修订，新建为 0
 * @param state PENDING、ACCEPTED 或 REJECTED
 * @param reason 来源及风险说明
 * @param revision 提案自身修订
 * @param createdAt 创建时间
 * @param updatedAt 处理时间
 */
public record WireSkillProposal(
        String id,
        String workspaceId,
        String targetId,
        String name,
        String version,
        String manifest,
        java.util.List<String> sourceItemIds,
        long expectedTargetRevision,
        String state,
        String reason,
        long revision,
        java.time.Instant createdAt,
        java.time.Instant updatedAt) {
    /** 防御性复制来源列表，不允许客户端修改已接收的审阅快照。 */
    public WireSkillProposal {
        sourceItemIds = java.util.List.copyOf(sourceItemIds);
    }
}
