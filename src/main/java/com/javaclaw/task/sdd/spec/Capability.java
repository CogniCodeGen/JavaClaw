package com.javaclaw.task.sdd.spec;

import java.util.List;

/**
 * 一个能力（capability）—— OpenSpec 规格的组织单元，一份 {@code specs/{能力名}/spec.md}。
 *
 * <p>一次变更可影响多个能力；每个能力下挂若干需求（{@link Requirement}）。能力名用作
 * 目录名，故约定 kebab-case 或简洁中文短名。</p>
 *
 * @param name         能力名（同时是 {@code specs/} 下的目录名）
 * @param requirements 该能力的需求列表
 * @author JavaClaw
 */
public record Capability(String name, List<Requirement> requirements) {

    public Capability {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }

    /** 展开本能力下所有场景（跨需求扁平化），供验证层逐条求值。 */
    public List<Scenario> allScenarios() {
        return requirements.stream().flatMap(r -> r.scenarios().stream()).toList();
    }
}
