package com.javaclaw.core.api;

import java.util.UUID;

/**
 * 统一输入输出 Item 的强类型标识。
 *
 * @param value 去除首尾空白后的非空标识
 */
public record ItemId(String value) {
    /** 去除标识首尾空白；null 或空白标识不合法。 */
    public ItemId {
        value = ThreadId.required(value, "itemId");
    }

    /** 生成带 item_ 前缀的随机 Item 标识；不创建持久记录。 */
    public static ItemId random() {
        return new ItemId("item_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public String toString() {
        return value;
    }
}
