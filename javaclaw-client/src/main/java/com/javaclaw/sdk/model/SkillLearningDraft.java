package com.javaclaw.sdk.model;

import java.util.List;

/**
 * 提交供用户审阅的 Skill 候选，不能借此自动批准脚本或授予权限。
 *
 * @param workspaceId 来源工作区
 * @param targetId 可选目标；null 时服务器进行重复检测
 * @param name 展示名
 * @param version 版本字符串
 * @param manifest 有界 JSON 声明正文
 * @param sourceItemIds 可核验的成功执行 Item，至少一个
 * @param expectedTargetRevision 固定目标修订，新建为 0
 */
public record SkillLearningDraft(
        String workspaceId,
        String targetId,
        String name,
        String version,
        String manifest,
        List<String> sourceItemIds,
        long expectedTargetRevision) {
    /** 复制来源，避免异步请求期间调用者修改候选证据。 */
    public SkillLearningDraft {
        sourceItemIds = List.copyOf(sourceItemIds);
    }
}
