package com.javaclaw.sdk.model;

/**
 * 审批 Item 的类型化内容；批准不会取消底层沙箱约束。
 *
 * @param kind 稳定类型标记；读取方应保留或忽略未知种类
 * @param approvalId 审批请求关联标识
 * @param reason 需要用户判断的原因或跳过说明
 * @param risk 服务端声明的风险级别
 * @param document 完整 JSON 文档，保留尚未类型化的扩展字段
 */
public record ApprovalItemContent(String kind, String approvalId, String reason, String risk, JsonDocument document)
        implements ItemContent {}
