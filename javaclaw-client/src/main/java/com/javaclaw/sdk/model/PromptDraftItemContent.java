package com.javaclaw.sdk.model;

import java.util.List;

/**
 * 用户确认后才可保存的提示词建议，不是自动配置更新。
 *
 * @param profileId 生成时绑定的 Profile
 * @param expectedRevision 生成时的 Profile 版本，保存须再次校验
 * @param draft 建议的人设与业务约定原文
 * @param changes 重要变化说明
 * @param warnings 能力不匹配等提醒
 * @param document 完整 JSON，保留扩展字段
 */
public record PromptDraftItemContent(
        String profileId,
        long expectedRevision,
        String draft,
        List<String> changes,
        List<String> warnings,
        JsonDocument document)
        implements ItemContent {
    /** 防御性复制变化和警告；草稿不是对 Profile 的自动更新。 */
    public PromptDraftItemContent {
        changes = List.copyOf(changes);
        warnings = List.copyOf(warnings);
    }

    @Override
    public String kind() {
        return "promptDraft";
    }
}
