package com.javaclaw.protocol;

import java.time.Instant;

/**
 * 单个 Turn 的安全执行元数据；不包含凭据、原始配置 JSON 或隐藏推理。
 *
 * @param turnId Turn 标识
 * @param profileId Profile 标识；旧记录不可用时为空
 * @param provider 实际 Provider
 * @param model 实际模型
 * @param status 持久状态
 * @param startedAt 开始时间
 * @param completedAt 完成时间；非终态为空
 * @param inputTokens 输入 Token；未知时为空
 * @param outputTokens 输出 Token；未知时为空
 * @param reasoningTokens 推理 Token；未知时为空
 */
public record WireTurnExecutionSummary(
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
