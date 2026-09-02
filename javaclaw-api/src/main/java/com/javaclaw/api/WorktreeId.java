package com.javaclaw.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Worktree 稳定标识。
 *
 * @param value UUID 值
 */
public record WorktreeId(UUID value) {
    /** 校验 UUID。 */
    public WorktreeId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * 解析 wire 或数据库值。
     *
     * @param value UUID 字符串
     * @return Worktree 标识
     */
    public static WorktreeId parse(String value) {
        return new WorktreeId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
