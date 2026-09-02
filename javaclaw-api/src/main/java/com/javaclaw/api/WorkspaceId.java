package com.javaclaw.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Workspace 的稳定标识。
 *
 * @param value 非空 UUID
 */
public record WorkspaceId(UUID value) {
    /** 校验 UUID 不为空。 */
    public WorkspaceId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * 创建随机标识。
     *
     * @return 新 Workspace 标识
     */
    public static WorkspaceId random() {
        return new WorkspaceId(UUID.randomUUID());
    }

    /**
     * 解析规范 UUID。
     *
     * @param value UUID 文本
     * @return Workspace 标识
     */
    public static WorkspaceId parse(String value) {
        return new WorkspaceId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
