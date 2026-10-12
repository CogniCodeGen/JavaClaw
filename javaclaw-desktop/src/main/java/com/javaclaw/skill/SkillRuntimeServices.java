package com.javaclaw.skill;

import com.javaclaw.skill.curation.SkillProposalQueue;

import java.util.Objects;

/**
 * 一个工作区内共享的技能运行时协作者。
 *
 * <p>该值由工作区 Spring Context 创建并注入智能体运行时，保证聊天、定时任务、插件、
 * SDD 与管理界面访问同一组实例。它不拥有协作者生命周期；关闭由 Spring 按依赖反序处理。</p>
 */
public record SkillRuntimeServices(
        SkillManager manager,
        SkillUsageTracker usage,
        SkillProposalQueue proposals) {

    public SkillRuntimeServices {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(proposals, "proposals");
    }
}
