package com.javaclaw.task.sdd.spec;

import java.util.List;

/**
 * 一条可验证的需求 —— 一个标题 + 一组刻画其对外行为的场景。
 *
 * <p>对应 {@code spec.md} 里的 {@code ## 需求：xxx} 段，其下若干 {@code ### 场景：xxx}。
 * 需求描述"做什么 / 对外行为"，不写实现细节。一条需求"达成"当且仅当其所有场景的
 * 验收谓词都成立。</p>
 *
 * @param title     需求标题
 * @param scenarios 该需求下的场景列表（至少一个）
 * @author JavaClaw
 */
public record Requirement(String title, List<Scenario> scenarios) {

    public Requirement {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }
}
