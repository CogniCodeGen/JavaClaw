package com.javaclaw.sdk.model;

/**
 * 知识检索命中及其来源版本，用于上下文审计。
 *
 * @param sourceId 知识源标识
 * @param chunkId 命中 Chunk 的标识
 * @param displayName 展示文件名；不作为客户端或服务器文件路径
 * @param content 正文内容，不应含明文凭据
 * @param score 检索排序分数，不是概率
 * @param sourceRevision 检索时采用的知识源版本
 */
public record KnowledgeHitInfo(
        String sourceId, String chunkId, String displayName, String content, double score, long sourceRevision) {}
