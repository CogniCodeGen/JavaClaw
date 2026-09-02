package com.javaclaw.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * MCP Catalog 分页刷新的脱敏进度投影。
 *
 * @param endpointId Endpoint 标识
 * @param endpointRevision 本次发现冻结的 Endpoint revision
 * @param state 当前状态
 * @param pagesCompleted 已完成远端分页数
 * @param entriesDiscovered 已校验条目数
 * @param committedCatalogRevision 成功提交的 Catalog revision；非完成状态为空
 * @param detail 非内容型失败摘要
 * @param startedAt 开始时间
 * @param updatedAt 最近进度时间
 */
public record McpCatalogRefresh(
        String endpointId,
        long endpointRevision,
        McpCatalogRefreshState state,
        int pagesCompleted,
        int entriesDiscovered,
        Optional<Long> committedCatalogRevision,
        Optional<String> detail,
        Instant startedAt,
        Instant updatedAt) {
    /** 校验计数、终态字段与时间。 */
    public McpCatalogRefresh {
        endpointId = Preconditions.identifier(endpointId, "endpointId");
        endpointRevision = Preconditions.positive(endpointRevision, "endpointRevision");
        Objects.requireNonNull(state, "state");
        if (pagesCompleted < 0 || pagesCompleted > 50) {
            throw new IllegalArgumentException("pagesCompleted must be between 0 and 50");
        }
        if (entriesDiscovered < 0 || entriesDiscovered > 10_000) {
            throw new IllegalArgumentException("entriesDiscovered must be between 0 and 10000");
        }
        committedCatalogRevision = Objects.requireNonNull(committedCatalogRevision, "committedCatalogRevision");
        committedCatalogRevision.ifPresent(value -> Preconditions.positive(value, "committedCatalogRevision"));
        detail = Objects.requireNonNull(detail, "detail").map(value -> Preconditions.text(value, "detail"));
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("updatedAt must not precede startedAt");
        }
        if ((state == McpCatalogRefreshState.COMPLETED) != committedCatalogRevision.isPresent()) {
            throw new IllegalArgumentException("only completed refresh carries a catalog revision");
        }
        if ((state == McpCatalogRefreshState.FAILED) != detail.isPresent()) {
            throw new IllegalArgumentException("only failed refresh carries detail");
        }
    }
}
