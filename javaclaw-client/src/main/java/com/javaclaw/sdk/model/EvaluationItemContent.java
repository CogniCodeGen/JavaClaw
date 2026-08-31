package com.javaclaw.sdk.model;

/**
 * 由实际证据支持的验收结果，不把助手自述当作完成。
 *
 * @param scope 验收范围
 * @param passed 是否已通过
 * @param summary 用户可读依据
 * @param evidenceItemIds 实际验收证据
 * @param remaining 未满足条件
 * @param document 完整原始 JSON，保留未知扩展
 */
public record EvaluationItemContent(
        String scope,
        boolean passed,
        String summary,
        java.util.List<String> evidenceItemIds,
        java.util.List<String> remaining,
        JsonDocument document)
        implements ItemContent {
    /** 固定集合，防止界面修改事件快照。 */
    public EvaluationItemContent {
        evidenceItemIds = java.util.List.copyOf(evidenceItemIds);
        remaining = java.util.List.copyOf(remaining);
    }

    @Override
    public String kind() {
        return "evaluation";
    }
}
