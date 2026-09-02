package com.javaclaw.api;

/**
 * Turn 或 Profile 使用的精确 PermissionProfile 引用。
 *
 * @param id 权限配置标识
 * @param version 不可变版本
 */
public record PermissionProfileRef(String id, long version) {
    /** 校验引用。 */
    public PermissionProfileRef {
        id = Preconditions.identifier(id, "id");
        version = Preconditions.positive(version, "version");
    }
}
