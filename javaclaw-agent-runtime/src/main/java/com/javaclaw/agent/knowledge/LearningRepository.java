package com.javaclaw.agent.knowledge;

import java.time.Instant;
import java.util.List;

/** 学习偏好与 Skill 提案的权威边界；建议、自动与关闭不改变运行时安全权限。 */
public interface LearningRepository {
    /** 未配置时返回 SUGGEST、低风险 Memory 自动提取开启、revision=0。 */
    LearningSettings learningSettings(String workspaceId);

    /** 显式保存偏好；自动模式仍不能加入脚本、提升权限或覆盖用户修改。 */
    LearningSettings saveLearningSettings(
            String workspaceId,
            String skillMode,
            boolean memoryAutomatic,
            long expectedRevision,
            String idempotencyKey);

    /** 记录有证据的 Skill 候选；相同名称或完整正文优先生成更新提案，避免重复技能。 */
    SkillProposal proposeSkill(SkillDraft draft, boolean permitLowRiskAutomatic, String reason, String idempotencyKey);

    /** 返回工作区的可审阅与已处理提案；不执行其中的脚本。 */
    List<SkillProposal> skillProposals(String workspaceId);

    /** 显式接受或拒绝候选；目标与提案修订均需要复核，接受与保存 Skill 同事务。 */
    SkillProposal reviewSkillProposal(String proposalId, boolean accept, long expectedRevision, String idempotencyKey);

    /**
     * 工作区学习偏好。
     *
     * @param workspaceId 工作区标识
     * @param skillMode OFF、SUGGEST 或 AUTO
     * @param memoryAutomatic 是否允许有证据的低风险自动提取
     * @param revision 从 1 开始，未保存为 0
     * @param updatedAt 最后更新时间，未保存为 EPOCH
     */
    record LearningSettings(
            String workspaceId, String skillMode, boolean memoryAutomatic, long revision, Instant updatedAt) {}

    /**
     * Skill 学习候选；不接受模型指定的权限或执行入口。
     *
     * @param workspaceId 来源工作区
     * @param targetId 可选更新目标，为 null 时进行去重匹配
     * @param name 非空展示名
     * @param version 非空版本
     * @param manifest 受校验的指令、资源声明
     * @param sourceItemIds 可核验的成功执行证据，至少一个
     * @param expectedTargetRevision 已知目标修订，新建为 0
     */
    record SkillDraft(
            String workspaceId,
            String targetId,
            String name,
            String version,
            String manifest,
            List<String> sourceItemIds,
            long expectedTargetRevision) {
        /** 固定有界输入；服务器仍需查询真实证据与目标内容。 */
        public SkillDraft {
            workspaceId = com.javaclaw.core.api.ThreadId.required(workspaceId, "workspaceId");
            name = com.javaclaw.core.api.ThreadId.required(name, "name");
            version = com.javaclaw.core.api.ThreadId.required(version, "version");
            sourceItemIds = List.copyOf(sourceItemIds);
            if (name.length() > 500
                    || version.length() > 100
                    || sourceItemIds.isEmpty()
                    || sourceItemIds.size() > 25
                    || expectedTargetRevision < 0) {
                throw new IllegalArgumentException("invalid Skill proposal bounds");
            }
            com.javaclaw.agent.tool.SkillManifests.validate(manifest);
            MemorySafety.requireNoSecrets(manifest);
        }
    }

    /**
     * 保留建议与处理证据的 Skill 提案。
     *
     * @param id 提案标识
     * @param draft 候选内容和固定目标修订
     * @param state PENDING、ACCEPTED 或 REJECTED
     * @param reason 建议及风险说明
     * @param revision 提案乐观锁修订
     * @param createdAt 创建时间
     * @param updatedAt 最近处理时间
     */
    record SkillProposal(
            String id,
            SkillDraft draft,
            String state,
            String reason,
            long revision,
            Instant createdAt,
            Instant updatedAt) {}
}
