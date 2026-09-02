package com.javaclaw.extension.spi;

import java.util.Objects;
import java.util.Set;

/**
 * 安装后不可变的扩展清单。
 *
 * @param id 扩展标识
 * @param displayName 展示名称
 * @param version Bundle 版本
 * @param revision 平台分配的单调版本
 * @param contributionKinds 声明的贡献点类别
 * @param requirements 运行与权限约束
 */
public record ExtensionDescriptor(
        ExtensionId id,
        String displayName,
        String version,
        long revision,
        Set<ContributionKind> contributionKinds,
        ExtensionRequirements requirements) {
    /** 复制集合并校验版本。 */
    public ExtensionDescriptor {
        Objects.requireNonNull(id, "id");
        displayName = requireText(displayName, "displayName");
        version = requireText(version, "version");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        contributionKinds = Set.copyOf(contributionKinds);
        Objects.requireNonNull(requirements, "requirements");
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
