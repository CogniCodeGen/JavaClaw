package com.javaclaw.api;

import java.util.Objects;
import java.util.UUID;

/**
 * 单次 Turn 的稳定标识。
 *
 * @param value 非空 UUID
 */
public record TurnId(UUID value) {
    /** 校验 UUID 不为空。 */
    public TurnId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * 创建随机标识。
     *
     * @return 新 Turn 标识
     */
    public static TurnId random() {
        return new TurnId(UUID.randomUUID());
    }

    /**
     * 解析规范 UUID。
     *
     * @param value UUID 文本
     * @return Turn 标识
     */
    public static TurnId parse(String value) {
        return new TurnId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
