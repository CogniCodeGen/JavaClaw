package com.javaclaw.sdk.model;

/**
 * 工作区学习偏好；AUTO 不是权限授权。
 *
 * @param workspaceId 工作区标识
 * @param skillMode OFF、SUGGEST 或 AUTO
 * @param memoryAutomatic 是否允许低风险记忆自动提取
 * @param revision 从 1 开始；未保存为 0
 * @param updatedAt 更新时间，未保存为 EPOCH
 */
public record LearningSettingsInfo(
        String workspaceId, String skillMode, boolean memoryAutomatic, long revision, java.time.Instant updatedAt) {}
