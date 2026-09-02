package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP 分页目录。
 *
 * @param entries 本页条目
 * @param nextCursor 下一页 opaque cursor；末页为空
 */
public record McpCatalogPage(List<McpCatalogEntry> entries, Optional<String> nextCursor) {
    /** 复制条目并校验 cursor。 */
    public McpCatalogPage {
        entries = List.copyOf(entries);
        if (entries.size() > 200) {
            throw new IllegalArgumentException("MCP catalog page must not exceed 200 entries");
        }
        nextCursor =
                Objects.requireNonNull(nextCursor, "nextCursor").map(value -> Preconditions.text(value, "nextCursor"));
    }
}
