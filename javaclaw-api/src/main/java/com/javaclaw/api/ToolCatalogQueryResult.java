package com.javaclaw.api;

import java.util.List;
import java.util.Objects;

/**
 * 设置界面读取的权威工具目录切片。
 *
 * <p>{@code tools} 可以因查询词和页大小而缩小，{@code catalogRevision} 始终代表未分页的当前可执行目录，不能由客户端根据当前页推算。
 *
 * @param catalogRevision 当前可执行目录的权威版本
 * @param tools 符合本次查询的有界工具描述
 */
public record ToolCatalogQueryResult(long catalogRevision, List<ToolDescriptor> tools) {
    /** 校验目录版本并取得结果集合所有权。 */
    public ToolCatalogQueryResult {
        if (catalogRevision < 1) {
            throw new IllegalArgumentException("catalogRevision must be positive");
        }
        tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
        if (tools.size() > 100) {
            throw new IllegalArgumentException("tools must not exceed 100 items");
        }
    }
}
