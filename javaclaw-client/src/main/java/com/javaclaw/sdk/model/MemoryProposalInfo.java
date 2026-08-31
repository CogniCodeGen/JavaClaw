package com.javaclaw.sdk.model;

import java.time.Instant;

/**
 * Memory 提案SDK 投影；不把已建议误标为已保存。
 *
 * @param id 提案标识
 * @param draft 建议内容
 * @param expectedTargetRevision 目标修订
 * @param state 提案处理状态
 * @param reason 来源及风险摘要
 * @param revision 提案修订
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record MemoryProposalInfo(
        String id,
        MemoryDetailInfo draft,
        long expectedTargetRevision,
        String state,
        String reason,
        long revision,
        Instant createdAt,
        Instant updatedAt) {}
