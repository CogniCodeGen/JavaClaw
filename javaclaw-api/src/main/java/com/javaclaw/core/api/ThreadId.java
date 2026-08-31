package com.javaclaw.core.api;

import java.util.Objects;
import java.util.UUID;

/**
 * 持久对话边界的强类型标识，避免与 Turn 或 Item 标识混用。
 *
 * @param value 去除首尾空白后的非空标识
 */
public record ThreadId(String value) {
    /** 去除标识首尾空白；null 或空白标识不合法。 */
    public ThreadId {
        value = required(value, "threadId");
    }

    /** 生成带 thr_ 前缀的随机 Thread 标识；不创建持久记录。 */
    public static ThreadId random() {
        return new ThreadId("thr_" + UUID.randomUUID().toString().replace("-", ""));
    }

    /**
     * 校验并返回去除首尾空白后的字符串，name 用于定位不合法参数。
     *
     * @param value 待校验值，不可为 null 或空白
     * @param name 参数名称，用于异常说明
     * @return 规范化后的非空字符串
     * @throws NullPointerException value 为 null
     * @throws IllegalArgumentException value 仅包含空白
     */
    public static String required(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
