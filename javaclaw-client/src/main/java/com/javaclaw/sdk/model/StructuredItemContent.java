package com.javaclaw.sdk.model;

/**
 * A known non-text Item kind whose complete payload remains available as canonical JSON.
 *
 * @param kind 稳定类型标记；读取方应保留或忽略未知种类
 * @param document 完整 JSON 文档，保留尚未类型化的扩展字段
 */
public record StructuredItemContent(String kind, JsonDocument document) implements ItemContent {}
