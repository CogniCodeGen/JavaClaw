package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 平台写入 {@link OrchestratedTurnResult#output()} 的稳定摘要。
 *
 * @param assistantText 子 Turn 的可见输出
 * @param inputTokens 已知输入 token；崩溃恢复后无法重建时为 0
 * @param outputTokens 已知输出 token；崩溃恢复后无法重建时为 0
 * @param toolCalls 已执行或恢复的工具调用数
 * @param errorCode 失败代码
 * @param toolEvidence 从持久 Item 提取的真实工具证据
 */
public record OrchestratedTurnSummary(
        String assistantText,
        long inputTokens,
        long outputTokens,
        int toolCalls,
        Optional<String> errorCode,
        List<OrchestratedToolEvidence> toolEvidence) {
    /** 校验计数与失败信息。 */
    public OrchestratedTurnSummary {
        assistantText = Objects.requireNonNull(assistantText, "assistantText");
        if (inputTokens < 0 || outputTokens < 0 || toolCalls < 0) {
            throw new IllegalArgumentException("Turn summary counters must not be negative");
        }
        errorCode = Objects.requireNonNull(errorCode, "errorCode")
                .map(String::strip)
                .filter(value -> !value.isEmpty());
        toolEvidence = List.copyOf(toolEvidence);
    }
}
