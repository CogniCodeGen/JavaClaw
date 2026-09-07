package com.javaclaw.api;

import java.util.Objects;

/**
 * 人工采纳 Prompt 草稿后的原子业务结果。
 *
 * @param draft 保留草稿及采纳引用的最新快照
 * @param role 使用草稿正文创建的新 Agent Role revision
 */
public record PromptOptimizationAdoption(PromptOptimizationDraft draft, AgentRole role) {
    /** 校验草稿的采纳引用与 Role 一致。 */
    public PromptOptimizationAdoption {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(role, "role");
        AgentRoleRef adopted = draft.adoptedRole()
                .orElseThrow(() -> new IllegalArgumentException("draft must reference the adopted Role"));
        if (!adopted.equals(new AgentRoleRef(role.id(), role.revision()))) {
            throw new IllegalArgumentException("draft adoption reference must match Role");
        }
    }
}
