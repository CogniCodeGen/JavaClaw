package com.javaclaw.sdk.model;

/**
 * 用户、助手或推理摘要的文本 Item 内容；kind 保留具体语义。
 *
 * @param kind 稳定类型标记；读取方应保留或忽略未知种类
 * @param text 显示文本；推理类只允许可展示摘要
 * @param document 完整 JSON 文档，保留尚未类型化的扩展字段
 */
public record TextItemContent(String kind, String text, JsonDocument document) implements ItemContent {}
