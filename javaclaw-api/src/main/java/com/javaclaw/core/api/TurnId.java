package com.javaclaw.core.api;

import java.util.UUID;

/**
 * 一次请求执行的强类型标识。
 *
 * @param value 去除首尾空白后的非空标识
 */
public record TurnId(String value) {
    /** 去除标识首尾空白；null 或空白标识不合法。 */
    public TurnId {
        value = ThreadId.required(value, "turnId");
    }

    /** 生成带 turn_ 前缀的随机 Turn 标识；不创建持久记录。 */
    public static TurnId random() {
        return new TurnId("turn_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public String toString() {
        return value;
    }
}
