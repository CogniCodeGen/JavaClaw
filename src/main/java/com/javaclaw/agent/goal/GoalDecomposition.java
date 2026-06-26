package com.javaclaw.agent.goal;

import java.util.List;

/**
 * 目标分解结果：用户请求分解后的可验证目标列表 + 结构化成功准则。
 *
 * <p>{@link #criteria} 为结构化谓词列表，便于事后核验；{@link #successCriteria}
 * 为自由文本总结，向后兼容旧逻辑与人类可读展示。两者由 {@link GoalManager}
 * 一次模型调用同时产出。</p>
 */
public class GoalDecomposition {

    private final String originalRequest;
    private final List<String> goals;
    private final String successCriteria;
    private final List<SuccessCriterion> criteria;

    public GoalDecomposition(String originalRequest,
                             List<String> goals,
                             String successCriteria,
                             List<SuccessCriterion> criteria) {
        this.originalRequest = originalRequest;
        this.goals = goals != null ? goals : List.of();
        this.successCriteria = successCriteria != null ? successCriteria : "";
        this.criteria = criteria != null ? criteria : List.of();
    }

    /** 兼容旧调用：无结构化准则时使用 */
    public GoalDecomposition(String originalRequest, List<String> goals, String successCriteria) {
        this(originalRequest, goals, successCriteria, List.of());
    }

    public String getOriginalRequest() { return originalRequest; }
    public List<String> getGoals() { return goals; }
    public String getSuccessCriteria() { return successCriteria; }
    public List<SuccessCriterion> getCriteria() { return criteria; }

    public boolean hasGoals() {
        return !goals.isEmpty();
    }

    public boolean hasStructuredCriteria() {
        return !criteria.isEmpty();
    }

    /**
     * 构建注入编排器系统提示词的目标上下文段落。
     *
     * <p>有结构化准则时一并展示，方便编排器执行时即关注核验点。</p>
     */
    public String buildContextPrompt() {
        if (!hasGoals()) return "";
        StringBuilder sb = new StringBuilder("\n## 本次任务目标\n\n");
        for (int i = 0; i < goals.size(); i++) {
            sb.append(i + 1).append(". ").append(goals.get(i)).append("\n");
        }
        if (!successCriteria.isEmpty()) {
            sb.append("\n**成功准则**：").append(successCriteria).append("\n");
        }
        if (hasStructuredCriteria()) {
            sb.append("\n**核验点**：\n");
            for (SuccessCriterion c : criteria) {
                sb.append("- ").append(c).append("\n");
            }
        }
        return sb.toString();
    }
}
