package com.javaclaw.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Role 对能力和 Skill 的收窄；集合缺省表示继承，存在但为空表示全部禁用。
 *
 * @param capabilities 可见能力名称上限，不可为空 Optional
 * @param skills 允许的 Skill 标识上限，不可为空 Optional
 */
public record CapabilityNarrowing(Optional<Set<String>> capabilities, Optional<Set<String>> skills) {
    /** 复制集合，避免 Role 更新改变已冻结配置。 */
    public CapabilityNarrowing {
        capabilities = copy(capabilities, "capabilities");
        skills = copy(skills, "skills");
    }

    /** @return 不添加能力或 Skill 限制的 Role 配置 */
    public static CapabilityNarrowing inherit() {
        return new CapabilityNarrowing(Optional.empty(), Optional.empty());
    }

    private static Optional<Set<String>> copy(Optional<Set<String>> source, String name) {
        return Objects.requireNonNull(source, name)
                .map(values -> values.stream()
                        .map(value -> Preconditions.text(value, name))
                        .collect(Collectors.toUnmodifiableSet()));
    }
}
