package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * 工作区隔离的 H2 属性仓储。
 *
 * <p>实例由根 Spring Context 管理。命名空间替换在单一事务中完成，确保内存中已经
 * 删除的键不会在下一次加载时复活。该类不缓存工作区 ID；每次操作在入口捕获一次，
 * 因此同一次保存不会跨越工作区切换。</p>
 */
public final class SqlPropertyStore {

    private static final Logger log = LoggerFactory.getLogger(SqlPropertyStore.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Supplier<String> workspaceId;

    public SqlPropertyStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            Supplier<String> workspaceId) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    public Properties load(String namespace) {
        return load(namespace, currentWorkspaceId());
    }

    Properties load(String namespace, String targetWorkspaceId) {
        Properties properties = new Properties();
        try {
            jdbc.query("""
                    SELECT prop_key, prop_value
                    FROM app_properties
                    WHERE workspace_id = ? AND namespace = ?
                    ORDER BY prop_key
                    """, (org.springframework.jdbc.core.RowCallbackHandler) result ->
                            properties.setProperty(
                            result.getString("prop_key"),
                            Objects.requireNonNullElse(result.getString("prop_value"), "")),
                    targetWorkspaceId, namespace);
        } catch (DataAccessException failure) {
            log.error("读取 H2 配置失败: namespace={}, workspace={}",
                    namespace, targetWorkspaceId, failure);
        }
        return properties;
    }

    public boolean save(String namespace, Properties properties) {
        return save(namespace, properties, currentWorkspaceId());
    }

    boolean save(String namespace, Properties properties, String targetWorkspaceId) {
        Properties snapshot = new Properties();
        snapshot.putAll(Objects.requireNonNull(properties, "properties"));
        try {
            transactions.executeWithoutResult(status ->
                    replaceNamespace(targetWorkspaceId, namespace, snapshot));
            return true;
        } catch (DataAccessException failure) {
            log.error("保存 H2 配置失败: namespace={}, workspace={}",
                    namespace, targetWorkspaceId, failure);
            return false;
        }
    }

    boolean saveProperty(String namespace, String key, String value, String targetWorkspaceId) {
        try {
            jdbc.update("""
                    MERGE INTO app_properties(
                        workspace_id, namespace, prop_key, prop_value, updated_at)
                    KEY (workspace_id, namespace, prop_key)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, targetWorkspaceId, namespace, key, value);
            return true;
        } catch (DataAccessException failure) {
            log.error("保存 H2 配置项失败: namespace={}, key={}, workspace={}",
                    namespace, key, targetWorkspaceId, failure);
            return false;
        }
    }

    String currentWorkspaceId() {
        String value = workspaceId.get();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("当前工作区 ID 尚未初始化");
        }
        return value;
    }

    /** 必须在 {@link #transactions} 的事务回调中调用。 */
    private void replaceNamespace(
            String targetWorkspaceId, String namespace, Properties properties) {
        jdbc.update("DELETE FROM app_properties WHERE workspace_id = ? AND namespace = ?",
                targetWorkspaceId, namespace);
        List<String> keys = properties.stringPropertyNames().stream().sorted().toList();
        if (keys.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO app_properties(
                    workspace_id, namespace, prop_key, prop_value, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, keys, keys.size(), (statement, key) -> {
                    statement.setString(1, targetWorkspaceId);
                    statement.setString(2, namespace);
                    statement.setString(3, key);
                    statement.setString(4, properties.getProperty(key));
                });
    }
}
