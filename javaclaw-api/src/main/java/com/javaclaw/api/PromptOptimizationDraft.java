package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;

/**
 * 可恢复的 Prompt 优化草稿快照。
 *
 * <p>正文只从普通 Turn 的 assistant Item 投影，不复制 Skill、Context、Secret 或完整 Prompt manifest。
 *
 * @param ref 优化任务与 Thread/Turn 关联
 * @param result 权威 Turn/Item 投影
 * @param provenance 内置优化说明来源
 * @param adoptedRole 已显式采纳后产生的新 Role；尚未采纳时为空
 */
public record PromptOptimizationDraft(
        PromptOptimizationRef ref,
        PromptOptimizationResult result,
        PromptOptimizationProvenance provenance,
        Optional<AgentRoleRef> adoptedRole) {
    /** 校验快照组成。 */
    public PromptOptimizationDraft {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(provenance, "provenance");
        adoptedRole = Objects.requireNonNull(adoptedRole, "adoptedRole");
        adoptedRole.ifPresent(role -> {
            if (!role.id().equals(ref.sourceRole().id())
                    || role.revision() <= ref.sourceRole().revision()) {
                throw new IllegalArgumentException("adopted Role must be a newer source Role revision");
            }
        });
    }
}
