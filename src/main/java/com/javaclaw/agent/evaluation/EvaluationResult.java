package com.javaclaw.agent.evaluation;

import java.util.List;

/**
 * 过程评估结果：包含综合评分、摘要和改进建议
 */
public class EvaluationResult {

    private final double score;
    private final String summary;
    private final boolean needsCorrection;
    private final List<String> suggestions;

    public EvaluationResult(double score, String summary, boolean needsCorrection, List<String> suggestions) {
        this.score = score;
        this.summary = summary != null ? summary : "";
        this.needsCorrection = needsCorrection;
        this.suggestions = suggestions != null ? suggestions : List.of();
    }

    public double getScore() { return score; }
    public String getSummary() { return summary; }
    public boolean isNeedsCorrection() { return needsCorrection; }
    public List<String> getSuggestions() { return suggestions; }

    public String formatForDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 **过程评估** — 综合评分：").append(String.format("%.1f", score)).append("/5.0");
        if (!summary.isEmpty()) sb.append("\n").append(summary);
        if (needsCorrection && !suggestions.isEmpty()) {
            sb.append("\n💡 改进建议：");
            for (String s : suggestions) sb.append("\n• ").append(s);
        }
        return sb.toString();
    }
}
