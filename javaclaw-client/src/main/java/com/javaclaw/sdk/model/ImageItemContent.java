package com.javaclaw.sdk.model;

/**
 * 可展示图像引用；UI 不应自动访问未知外部 URI。
 *
 * @param uri 服务器提供的附件引用
 * @param description 图像说明
 * @param document 完整原始 JSON，保留未知扩展
 */
public record ImageItemContent(String uri, String description, JsonDocument document) implements ItemContent {
    @Override
    public String kind() {
        return "imageView";
    }
}
