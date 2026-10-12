package com.javaclaw.task.sdd.spec;

/**
 * 规格中的单个可观测行为场景 —— Given/When/Then 三段式 + 一条验收谓词。
 *
 * <p>场景是 OpenSpec 规格（{@code spec.md}）的最小验证单元：{@link #criterion} 即本场景
 * "做到没有"的判据，由 {@code ScenarioVerifier} 统一求值（确定性谓词代码核验、
 * freeform 交 critic）。这取代了 v5 里散落的执行体自检 + fact-check 闸门 + 质疑临时判据。</p>
 *
 * @param title     场景标题（如"红方走子合法性校验"）
 * @param given     前置条件
 * @param when      触发动作
 * @param then      期望结果
 * @param criterion 该场景的验收谓词
 * @author JavaClaw
 */
public record Scenario(String title, String given, String when, String then, Criterion criterion) {
}
