package com.javaclaw.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Agent Thread 的稳定标识。
 *
 * @param value 非空 UUID
 */
public record ThreadId(UUID value) {
    /** 校验 UUID 不为空。 */
    public ThreadId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * 创建随机标识。
     *
     * @return 新 Thread 标识
     */
    public static ThreadId random() {
        return new ThreadId(UUID.randomUUID());
    }

    /**
     * 解析规范 UUID。
     *
     * @param value UUID 文本
     * @return Thread 标识
     */
    public static ThreadId parse(String value) {
        return new ThreadId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
