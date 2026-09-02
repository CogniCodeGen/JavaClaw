package com.javaclaw.api;

import java.util.List;
import java.util.Objects;

/**
 * MCP Resource 显式读取结果；内容始终是外部数据，不会自动进入 system context。
 *
 * @param contents 有界内容块
 * @param cacheScope 远端缓存作用域
 * @param ttlMilliseconds 远端缓存提示，单位毫秒
 * @param progress 已校验的进度通知
 */
public record McpResourceReadResult(
        List<McpResourceContent> contents, McpCacheScope cacheScope, long ttlMilliseconds, List<McpProgress> progress) {
    /** 复制并校验读取结果。 */
    public McpResourceReadResult {
        contents = List.copyOf(contents);
        if (contents.size() > 64) {
            throw new IllegalArgumentException("resource result must not exceed 64 contents");
        }
        Objects.requireNonNull(cacheScope, "cacheScope");
        ttlMilliseconds = Preconditions.nonNegative(ttlMilliseconds, "ttlMilliseconds");
        progress = List.copyOf(progress);
    }
}
