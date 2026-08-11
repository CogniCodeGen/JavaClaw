package com.javaclaw.plugin.capability;

import com.javaclaw.plugin.CapabilityGuard;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.capability.StorageAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 插件 STORAGE 能力的 JDBC 实现。
 *
 * <p>实例绑定不可变的工作区和插件标识，由插件运行时持有。每次调用先校验插件身份；
 * 写操作在 Spring 事务内使用 H2 原子 MERGE，避免全表快照重写以及并发插入竞争。</p>
 */
public final class StorageAccessImpl implements StorageAccess {

    private final String pluginId;
    private final String workspaceId;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public StorageAccessImpl(
            String pluginId,
            String workspaceId,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.workspaceId = requireText(workspaceId, "workspaceId");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public String get(String key) {
        CapabilityGuard.require(Capability.STORAGE);
        List<String> values = jdbc.query(
                """
                        SELECT store_value
                        FROM plugin_storage
                        WHERE workspace_id = ? AND plugin_id = ? AND store_key = ?
                        """,
                (row, index) -> row.getString("store_value"),
                workspaceId, pluginId, requireText(key, "key"));
        return values.isEmpty() ? "" : Objects.toString(values.getFirst(), "");
    }

    @Override
    public void put(String key, String value) {
        CapabilityGuard.require(Capability.STORAGE);
        String checkedKey = requireText(key, "key");
        String storedValue = value == null ? "" : value;
        transaction.executeWithoutResult(status -> jdbc.update("""
                        MERGE INTO plugin_storage(
                            workspace_id, plugin_id, store_key, store_value, updated_at)
                        KEY(workspace_id, plugin_id, store_key)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                workspaceId, pluginId, checkedKey, storedValue));
    }

    @Override
    public void remove(String key) {
        CapabilityGuard.require(Capability.STORAGE);
        jdbc.update("""
                        DELETE FROM plugin_storage
                        WHERE workspace_id = ? AND plugin_id = ? AND store_key = ?
                        """,
                workspaceId, pluginId, requireText(key, "key"));
    }

    @Override
    public Set<String> keys() {
        CapabilityGuard.require(Capability.STORAGE);
        List<String> keys = jdbc.query("""
                        SELECT store_key
                        FROM plugin_storage
                        WHERE workspace_id = ? AND plugin_id = ?
                        ORDER BY store_key
                        """,
                (row, index) -> row.getString("store_key"), workspaceId, pluginId);
        return new LinkedHashSet<>(keys);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        return value;
    }
}
