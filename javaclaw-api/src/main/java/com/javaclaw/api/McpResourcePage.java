package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP Resource 分页结果。
 *
 * @param resources 本页外部资源
 * @param nextCursor 下一页远端 opaque cursor
 * @param cacheScope 远端缓存作用域
 * @param ttlMilliseconds 远端缓存提示，单位毫秒
 * @param progress 已校验且按接收顺序排列的进度
 */
public record McpResourcePage(
        List<McpResourceDescriptor> resources,
        Optional<String> nextCursor,
        McpCacheScope cacheScope,
        long ttlMilliseconds,
        List<McpProgress> progress) {
    /** 复制并校验分页结果。 */
    public McpResourcePage {
        resources = List.copyOf(resources);
        if (resources.size() > 200) {
            throw new IllegalArgumentException("resource page must not exceed 200 entries");
        }
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor")
                .map(value -> McpResourceDescriptor.boundedText(value, "nextCursor", 4_096));
        Objects.requireNonNull(cacheScope, "cacheScope");
        ttlMilliseconds = Preconditions.nonNegative(ttlMilliseconds, "ttlMilliseconds");
        progress = List.copyOf(progress);
    }
}
