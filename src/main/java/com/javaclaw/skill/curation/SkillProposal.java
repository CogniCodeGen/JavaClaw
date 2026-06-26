package com.javaclaw.skill.curation;

import com.javaclaw.skill.SkillChangeRequest;

/**
 * 技能变更提案 —— suggest 模式下待用户审阅的一条技能变更。
 *
 * <p>来源有两路：智能体主动调用 skill_manage 工具（nudge 路径），
 * 或 SkillCurator 轮后自动蒸馏（兜底路径）；两路统一经
 * {@link SkillProposalQueue} 指纹去重。公开字段 + 无参构造，Jackson 友好。</p>
 *
 * @author JavaClaw
 */
public class SkillProposal {

    /** 提案状态 */
    public enum Status { PENDING, APPROVED, REJECTED }

    /** 提案 ID（入队时生成） */
    public String id;

    /** 结构化变更请求（采纳后经 apply() 落盘） */
    public SkillChangeRequest request;

    /** 提案创建时间（epoch 毫秒） */
    public long createdAt;

    /** 当前状态 */
    public Status status = Status.PENDING;

    /** 终态时间（采纳/拒绝时刻，epoch 毫秒；被拒提案据此计算冷却期） */
    public long resolvedAt;

    public SkillProposal() {
    }

    public SkillProposal(String id, SkillChangeRequest request, long createdAt) {
        this.id = id;
        this.request = request;
        this.createdAt = createdAt;
    }
}
