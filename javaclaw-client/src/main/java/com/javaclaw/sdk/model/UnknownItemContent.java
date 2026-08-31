package com.javaclaw.sdk.model;

/**
 * 旧客户端尚不认识的 Item；保留完整 JSON 以便无损恢复和转交，不中断会话。
 *
 * @param kind 稳定类型标记；读取方应保留或忽略未知种类
 * @param document 完整 JSON 文档，保留尚未类型化的扩展字段
 */
public record UnknownItemContent(String kind, JsonDocument document) implements ItemContent {}
