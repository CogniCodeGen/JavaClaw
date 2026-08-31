package com.javaclaw.sdk.model;

import java.util.List;

/**
 * 已校验的规划产物，不能推断计划已获执行授权。
 *
 * @param goal 任务目标，旧历史缺失时为空字符串
 * @param scope 任务范围，旧历史缺失时为空字符串
 * @param steps 有序步骤与状态
 * @param dependencies 依赖条件
 * @param acceptanceCriteria 可验证的验收标准
 * @param risks 已知风险
 * @param openQuestions 需要用户决策的问题
 * @param document 完整原始 JSON，保留未知扩展
 */
public record PlanItemContent(
        String goal,
        String scope,
        List<Step> steps,
        List<String> dependencies,
        List<String> acceptanceCriteria,
        List<String> risks,
        List<String> openQuestions,
        JsonDocument document)
        implements ItemContent {
    /** 固定所有集合，防止 UI 修改持久化快照的客户端投影。 */
    public PlanItemContent {
        steps = List.copyOf(steps);
        dependencies = List.copyOf(dependencies);
        acceptanceCriteria = List.copyOf(acceptanceCriteria);
        risks = List.copyOf(risks);
        openQuestions = List.copyOf(openQuestions);
    }

    @Override
    public String kind() {
        return "plan";
    }

    /**
     * 计划步骤投影。
     *
     * @param description 步骤说明
     * @param status 服务端状态，保留未知兼容扩展
     */
    public record Step(String description, String status) {}
}
