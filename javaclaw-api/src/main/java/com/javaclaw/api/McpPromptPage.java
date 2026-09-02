package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP Prompt 分页结果。
 *
 * @param prompts 本页模板
 * @param nextCursor 下一页远端 cursor
 * @param cacheScope 远端缓存作用域
 * @param ttlMilliseconds 缓存提示，单位毫秒
 * @param progress 已校验进度
 */
public record McpPromptPage(
        List<McpPromptDescriptor> prompts,
        Optional<String> nextCursor,
        McpCacheScope cacheScope,
        long ttlMilliseconds,
        List<McpProgress> progress) {
    /** 复制并校验分页结果。 */
    public McpPromptPage {
        prompts = List.copyOf(prompts);
        if (prompts.size() > 200) {
            throw new IllegalArgumentException("prompt page must not exceed 200 entries");
        }
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor")
                .map(value -> McpResourceDescriptor.boundedText(value, "nextCursor", 4_096));
        Objects.requireNonNull(cacheScope, "cacheScope");
        ttlMilliseconds = Preconditions.nonNegative(ttlMilliseconds, "ttlMilliseconds");
        progress = List.copyOf(progress);
    }
}
