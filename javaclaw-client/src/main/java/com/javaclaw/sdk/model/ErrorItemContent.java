package com.javaclaw.sdk.model;

/**
 * 脱敏错误 Item 内容，retryable 仅作为调用方重试提示。
 *
 * @param kind 稳定类型标记；读取方应保留或忽略未知种类
 * @param code 稳定错误代码
 * @param message 脱敏错误说明或展示摘要
 * @param retryable 是否允许在原有安全约束下尝试重试
 * @param document 完整 JSON 文档，保留尚未类型化的扩展字段
 */
public record ErrorItemContent(String kind, String code, String message, boolean retryable, JsonDocument document)
        implements ItemContent {}
