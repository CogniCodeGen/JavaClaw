package com.javaclaw.runtime;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 从持久日志恢复的 Harness 精确执行状态。
 *
 * @param phase 已提交相位
 * @param usage 已提交模型用量
 * @param toolCalls 已扣减工具调用次数
 * @param modelInvocations 已提交的模型调用意图次数
 * @param toolBatch 当前模型结果的工具批次
 * @param nextToolIndex 下一个待执行调用下标
 * @param visibleTools 已向模型公开的冻结工具
 * @param seenCallIds 已持久化的调用 ID，用于跨重启拒绝重复 ID
 * @param assistantText 本 Turn 已持久化的 Assistant 文本
 * @param activeIntentDigest 外部调用中的脱敏意图摘要；仅 in-flight 相位存在
 */
public record TurnRecoverySnapshot(
        TurnExecutionPhase phase,
        ModelUsage usage,
        int toolCalls,
        int modelInvocations,
        TurnToolBatch toolBatch,
        int nextToolIndex,
        TurnVisibleTools visibleTools,
        Set<String> seenCallIds,
        String assistantText,
        Optional<String> activeIntentDigest) {
    /** 校验相位、批次位置与不明确结果证据的一致性。 */
    public TurnRecoverySnapshot {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(usage, "usage");
        if (toolCalls < 0 || modelInvocations < 0) {
            throw new IllegalArgumentException("execution counters must not be negative");
        }
        Objects.requireNonNull(toolBatch, "toolBatch");
        if (nextToolIndex < 0 || nextToolIndex > toolBatch.calls().size()) {
            throw new IllegalArgumentException("nextToolIndex is outside the tool batch");
        }
        Objects.requireNonNull(visibleTools, "visibleTools");
        seenCallIds = Set.copyOf(new LinkedHashSet<>(seenCallIds));
        if (seenCallIds.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("seen call IDs must not be blank");
        }
        assistantText = Objects.requireNonNull(assistantText, "assistantText");
        activeIntentDigest = Objects.requireNonNull(activeIntentDigest, "activeIntentDigest")
                .map(value -> requireDigest(value, "activeIntentDigest"));
        requirePhaseShape(phase, toolBatch, nextToolIndex, activeIntentDigest);
    }

    /**
     * 创建尚未执行外部调用的初始状态。
     *
     * @return 零消费恢复状态
     */
    public static TurnRecoverySnapshot initial() {
        return new TurnRecoverySnapshot(
                TurnExecutionPhase.READY_FOR_MODEL,
                ModelUsage.zero(),
                0,
                0,
                TurnToolBatch.empty(),
                0,
                TurnVisibleTools.empty(),
                Set.of(),
                "",
                Optional.empty());
    }

    private static void requirePhaseShape(
            TurnExecutionPhase phase, TurnToolBatch batch, int nextToolIndex, Optional<String> activeIntentDigest) {
        boolean toolsPhase = phase.toolBatchPhase();
        if (toolsPhase != !batch.calls().isEmpty()
                || toolsPhase && nextToolIndex >= batch.calls().size()) {
            throw new IllegalArgumentException("execution phase and tool batch differ");
        }
        if (phase.requiresIntentDigest() != activeIntentDigest.isPresent()) {
            throw new IllegalArgumentException("committed intent phase requires an intent digest");
        }
    }

    private static String requireDigest(String value, String name) {
        String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return normalized;
    }
}
