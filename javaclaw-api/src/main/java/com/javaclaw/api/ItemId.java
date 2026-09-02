package com.javaclaw.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Item 的稳定标识。
 *
 * @param value 非空 UUID
 */
public record ItemId(UUID value) {
    /** 校验 UUID 不为空。 */
    public ItemId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * 创建随机标识。
     *
     * @return 新 Item 标识
     */
    public static ItemId random() {
        return new ItemId(UUID.randomUUID());
    }

    /**
     * 解析规范 UUID。
     *
     * @param value UUID 文本
     * @return Item 标识
     */
    public static ItemId parse(String value) {
        return new ItemId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
