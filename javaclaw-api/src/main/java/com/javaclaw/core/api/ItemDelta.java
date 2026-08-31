package com.javaclaw.core.api;

import java.util.Map;

/**
 * Ephemeral high-frequency content fragment; never consumes Thread sequence.
 *
 * @param contentType 非空白增量内容类型
 * @param text 本次新增文本；null 归一为空字符串
 * @param metadata 增量附加元数据；null 归一为空 Map
 */
public record ItemDelta(String contentType, String text, Map<String, String> metadata) {
    /** 校验内容类型并固定元数据；增量只进入内存流，不消耗持久事件序号。 */
    public ItemDelta {
        contentType = ThreadId.required(contentType, "contentType");
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** 创建 text/plain 增量；null 文本按空片段处理，元数据为空。 */
    public static ItemDelta text(String value) {
        return new ItemDelta("text/plain", value, Map.of());
    }
}
