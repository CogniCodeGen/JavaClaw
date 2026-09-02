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
 * @param adoptedProfile 已显式采纳后产生的新 Profile；尚未采纳时为空
 */
public record PromptOptimizationDraft(
        PromptOptimizationRef ref,
        PromptOptimizationResult result,
        PromptOptimizationProvenance provenance,
        Optional<AgentProfileRef> adoptedProfile) {
    /** 校验快照组成。 */
    public PromptOptimizationDraft {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(provenance, "provenance");
        adoptedProfile = Objects.requireNonNull(adoptedProfile, "adoptedProfile");
        adoptedProfile.ifPresent(profile -> {
            if (!profile.id().equals(ref.sourceProfile().id())
                    || profile.revision() <= ref.sourceProfile().revision()) {
                throw new IllegalArgumentException("adopted Profile must be a newer source Profile revision");
            }
        });
    }
}
