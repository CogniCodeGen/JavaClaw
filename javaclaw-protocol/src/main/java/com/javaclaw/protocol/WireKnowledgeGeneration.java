package com.javaclaw.protocol;

/**
 * 已完成的知识索引 generation；未完成重建不会成为当前投影。
 *
 * @param sourceId 知识源标识
 * @param revision 切换时的源修订
 * @param contentSha256 正文摘要
 * @param extractorFingerprint 解析器版本摘要
 * @param status 索引或降级状态
 * @param createdAt 切换时间
 */
public record WireKnowledgeGeneration(
        String sourceId,
        long revision,
        String contentSha256,
        String extractorFingerprint,
        String status,
        java.time.Instant createdAt) {}
