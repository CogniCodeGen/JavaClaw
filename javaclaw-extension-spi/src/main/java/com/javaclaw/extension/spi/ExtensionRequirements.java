package com.javaclaw.extension.spi;

import java.util.Objects;

import com.javaclaw.api.PermissionProfile;

/**
 * 扩展运行约束。
 *
 * @param trust 执行信任层
 * @param availability 是否允许管理员停用
 * @param minimumProtocolVersion 所需最小 App Protocol 版本
 * @param permissionCeiling 扩展声明的最大权限
 */
public record ExtensionRequirements(
        ExtensionTrust trust,
        ExtensionAvailability availability,
        int minimumProtocolVersion,
        PermissionProfile permissionCeiling) {
    /** 校验协议基线和权限。 */
    public ExtensionRequirements {
        Objects.requireNonNull(trust, "trust");
        Objects.requireNonNull(availability, "availability");
        if (minimumProtocolVersion < 2) {
            throw new IllegalArgumentException("minimumProtocolVersion must be at least 2");
        }
        Objects.requireNonNull(permissionCeiling, "permissionCeiling");
    }
}
