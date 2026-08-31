package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * 单个 Turn 的安全执行摘要。
 *
 * @param turnId Turn 标识
 * @param profileId 启动时解析的 Profile 标识；旧记录不可用时为空
 * @param provider 实际 Provider 标识
 * @param model 实际模型标识
 * @param status 持久状态
 * @param startedAt 开始时间
 * @param completedAt 完成时间；非终态为空
 * @param inputTokens 输入 Token；不可用时为 null
 * @param outputTokens 输出 Token；不可用时为 null
 * @param reasoningTokens 推理 Token；不可用时为 null
 */
public record TurnExecutionSummaryInfo(
        String turnId,
        String profileId,
        String provider,
        String model,
        String status,
        Instant startedAt,
        Instant completedAt,
        Long inputTokens,
        Long outputTokens,
        Long reasoningTokens) {}
