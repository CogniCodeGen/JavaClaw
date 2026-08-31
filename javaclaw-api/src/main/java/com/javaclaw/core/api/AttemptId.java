package com.javaclaw.core.api;

import java.util.UUID;

/**
 * 内部执行尝试标识，仅用于诊断和恢复，不替代 Thread/Turn 产品模型。
 *
 * @param value 去除首尾空白后的非空标识
 */
public record AttemptId(String value) {
    /** 去除标识首尾空白；null 或空白标识不合法。 */
    public AttemptId {
        value = ThreadId.required(value, "attemptId");
    }

    /** 生成带 attempt_ 前缀的随机执行尝试标识；不创建持久记录。 */
    public static AttemptId random() {
        return new AttemptId("attempt_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public String toString() {
        return value;
    }
}
