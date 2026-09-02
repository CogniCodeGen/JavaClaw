package com.javaclaw.api;

import java.util.Objects;

/**
 * 人工采纳 Prompt 草稿后的原子业务结果。
 *
 * @param draft 保留草稿及采纳引用的最新快照
 * @param profile 使用草稿正文创建的新 Agent Profile revision
 */
public record PromptOptimizationAdoption(PromptOptimizationDraft draft, AgentProfile profile) {
    /** 校验草稿的采纳引用与 Profile 一致。 */
    public PromptOptimizationAdoption {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(profile, "profile");
        AgentProfileRef adopted = draft.adoptedProfile()
                .orElseThrow(() -> new IllegalArgumentException("draft must reference the adopted Profile"));
        if (!adopted.equals(new AgentProfileRef(profile.id(), profile.revision()))) {
            throw new IllegalArgumentException("draft adoption reference must match Profile");
        }
    }
}
