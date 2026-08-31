package com.javaclaw.sdk.model;

import java.util.List;

/**
 * 等待用户回答的问题 Item，选项为空时表示自由输入。
 *
 * @param kind 稳定类型标记；读取方应保留或忽略未知种类
 * @param requestId 用户输入问题关联标识
 * @param prompt 展示问题或执行提示词；不能被用作权限声明
 * @param choices 可选回答列表；null 归一为空列表并复制
 * @param document 完整 JSON 文档，保留尚未类型化的扩展字段
 */
public record UserInputItemContent(
        String kind, String requestId, String prompt, List<String> choices, JsonDocument document)
        implements ItemContent {
    /** 复制可选回答集合；保留问题标识以与用户响应一一关联。 */
    public UserInputItemContent {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }
}
