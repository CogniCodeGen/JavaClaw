package com.javaclaw.sdk.model;

/**
 * 自动化恢复点；恢复必须新建 Turn，不能回拨旧状态。
 *
 * @param executionId 逻辑执行标识
 * @param definitionHash 冻结定义摘要
 * @param stepId 下一步骤
 * @param status 恢复点状态
 * @param iteration 已完成迭代或步骤数
 * @param usedModelCalls 已消费调用数
 * @param usedTokens 已消费 token 数
 * @param elapsedMillis 累计活动毫秒
 * @param summary 进展与未完成条件
 * @param document 完整原始 JSON，保留未知扩展
 */
public record CheckpointItemContent(
        String executionId,
        String definitionHash,
        String stepId,
        String status,
        int iteration,
        int usedModelCalls,
        long usedTokens,
        long elapsedMillis,
        String summary,
        JsonDocument document)
        implements ItemContent {
    @Override
    public String kind() {
        return "checkpoint";
    }
}
