package com.javaclaw.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Agent Profile Prompt 优化任务的稳定标识。
 *
 * @param value 非空 UUID
 */
public record PromptOptimizationId(UUID value) {
    /** 校验 UUID 不为空。 */
    public PromptOptimizationId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * 创建随机标识。
     *
     * @return 新优化任务标识
     */
    public static PromptOptimizationId random() {
        return new PromptOptimizationId(UUID.randomUUID());
    }

    /**
     * 解析规范 UUID。
     *
     * @param value UUID 文本
     * @return 优化任务标识
     */
    public static PromptOptimizationId parse(String value) {
        return new PromptOptimizationId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
