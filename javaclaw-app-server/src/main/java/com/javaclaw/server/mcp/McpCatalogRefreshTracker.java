package com.javaclaw.server.mcp;

import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.McpCatalogRefresh;
import com.javaclaw.api.McpCatalogRefreshState;
import com.javaclaw.api.McpEndpoint;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2Transactions;
import com.javaclaw.server.persistence.PersistenceException;

/**
 * MCP Catalog 远端发现进度的持久化协调器。
 *
 * <p>运行中进度允许独立提交，最终 COMPLETED 必须由调用方放入 Catalog/Endpoint 同一事务；失败只保存非内容型异常类名，旧 Catalog 始终保持可读。
 */
final class McpCatalogRefreshTracker {
    private final H2Transactions transactions;
    private final McpCatalogRefreshRepository repository = new McpCatalogRefreshRepository();
    private final Clock clock;

    /**
     * 创建进度协调器。
     *
     * @param database data-v5 数据库
     * @param clock 平台时钟
     */
    McpCatalogRefreshTracker(H2Database database, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @param endpointId Endpoint 标识 @return 最近一次进度 */
    Optional<McpCatalogRefresh> find(String endpointId) {
        return execute(connection -> repository.find(connection, endpointId));
    }

    /** @param endpoint 本次冻结的 Endpoint */
    void start(McpEndpoint endpoint) {
        Instant now = clock.instant();
        save(new McpCatalogRefresh(
                endpoint.id(),
                endpoint.revision(),
                McpCatalogRefreshState.RUNNING,
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                now,
                now));
    }

    /**
     * 保存已完成分页数。
     *
     * @param endpoint 本次冻结的 Endpoint
     * @param pages 已完成页数
     * @param entries 已校验条目数
     */
    void progress(McpEndpoint endpoint, int pages, int entries) {
        McpCatalogRefresh current = running(endpoint);
        save(new McpCatalogRefresh(
                current.endpointId(),
                current.endpointRevision(),
                current.state(),
                pages,
                entries,
                Optional.empty(),
                Optional.empty(),
                current.startedAt(),
                clock.instant()));
    }

    /**
     * 将发现失败保存为脱敏终态。
     *
     * @param endpoint 本次冻结的 Endpoint
     * @param failure 失败原因；仅保存类型名
     */
    void fail(McpEndpoint endpoint, RuntimeException failure) {
        find(endpoint.id())
                .filter(value -> sameRunning(value, endpoint))
                .ifPresent(current -> save(new McpCatalogRefresh(
                        current.endpointId(),
                        current.endpointRevision(),
                        McpCatalogRefreshState.FAILED,
                        current.pagesCompleted(),
                        current.entriesDiscovered(),
                        Optional.empty(),
                        Optional.of(failure.getClass().getSimpleName()),
                        current.startedAt(),
                        clock.instant())));
    }

    /**
     * 在 Catalog 与 Endpoint 的同一事务中提交成功终态。
     *
     * @param connection 调用方事务连接
     * @param endpoint 已提交的新 Endpoint 版本
     * @param catalogRevision 新 Catalog revision
     * @param completedAt 提交时间
     * @throws Exception SQL 失败
     */
    void complete(Connection connection, McpEndpoint endpoint, long catalogRevision, Instant completedAt)
            throws Exception {
        McpCatalogRefresh running = repository
                .find(connection, endpoint.id())
                .filter(value -> value.state() == McpCatalogRefreshState.RUNNING)
                .orElseThrow(() -> PersistenceException.invalidRequest("MCP Catalog 刷新状态不存在"));
        repository.save(
                connection,
                new McpCatalogRefresh(
                        running.endpointId(),
                        running.endpointRevision(),
                        McpCatalogRefreshState.COMPLETED,
                        running.pagesCompleted(),
                        running.entriesDiscovered(),
                        Optional.of(catalogRevision),
                        Optional.empty(),
                        running.startedAt(),
                        completedAt));
    }

    private McpCatalogRefresh running(McpEndpoint endpoint) {
        return find(endpoint.id())
                .filter(value -> sameRunning(value, endpoint))
                .orElseThrow(() -> PersistenceException.revisionConflict("MCP Catalog 刷新状态已变化"));
    }

    private static boolean sameRunning(McpCatalogRefresh refresh, McpEndpoint endpoint) {
        return refresh.endpointRevision() == endpoint.revision() && refresh.state() == McpCatalogRefreshState.RUNNING;
    }

    private void save(McpCatalogRefresh refresh) {
        execute(connection -> {
            repository.save(connection, refresh);
            return null;
        });
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (PersistenceException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("MCP Catalog 进度持久化失败", failure);
        }
    }
}
