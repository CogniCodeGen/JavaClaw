package com.javaclaw.sdk.model;

/**
 * 工具发现视图；出现在目录不表示当前 Turn 已获授权。
 *
 * @param name 稳定工具名
 * @param description 能力说明
 * @param inputSchema 有界 JSON Schema
 */
public record ToolCapabilityInfo(String name, String description, JsonDocument inputSchema) {}
