package com.javaclaw.api;

import java.util.Objects;
import java.util.Set;

/**
 * 两个不可变 PermissionProfile revision 的强类型差异。
 *
 * @param before 较早或基准版本
 * @param after 较新或比较版本
 * @param changedSections 内容发生变化的分区
 */
public record PermissionProfileDiff(
        PermissionProfile before, PermissionProfile after, Set<PermissionSection> changedSections) {
    /** 复制分区并要求两个版本属于同一配置。 */
    public PermissionProfileDiff {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        changedSections = Set.copyOf(changedSections);
        if (!before.id().equals(after.id())) {
            throw new IllegalArgumentException("permission profile diff requires the same id");
        }
    }
}
