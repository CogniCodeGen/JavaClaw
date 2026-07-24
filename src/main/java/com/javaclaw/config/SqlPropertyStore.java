package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/**
 * 基于 H2 的配置属性存储。
 *
 * <p>每类配置使用一个 namespace，键值落在 app_properties 表中，并以
 * {@code workspace_id} 隔离。启动时只读取 H2，不再从旧目录或 properties 文件导入。</p>
 */
public final class SqlPropertyStore {

    private static final Logger log = LoggerFactory.getLogger(SqlPropertyStore.class);

    private SqlPropertyStore() {}

    public static Properties load(String namespace) {
        return loadNamespace(namespace);
    }

    public static boolean save(String namespace, Properties props) {
        return save(namespace, props, AppDatabase.currentWorkspaceId());
    }

    static boolean save(String namespace, Properties props, String workspaceId) {
        try (Connection c = AppDatabase.getConnection()) {
            c.setAutoCommit(false);
            replaceNamespace(c, workspaceId, namespace, props);
            c.commit();
            return true;
        } catch (SQLException e) {
            log.error("保存 H2 配置失败: namespace={}", namespace, e);
            return false;
        }
    }

    static boolean saveProperty(String namespace, String key, String value, String workspaceId) {
        String sql = """
                MERGE INTO app_properties(workspace_id, namespace, prop_key, prop_value, updated_at)
                KEY (workspace_id, namespace, prop_key)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            ps.setString(2, namespace);
            ps.setString(3, key);
            ps.setString(4, value);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.error("保存 H2 配置项失败: namespace={}, key={}", namespace, key, e);
            return false;
        }
    }

    /** 在调用方事务内完整替换命名空间，使内存中已删除的键不会在重载时复活。 */
    static void replaceNamespace(Connection c, String workspaceId, String namespace, Properties props)
            throws SQLException {
        try (PreparedStatement delete = c.prepareStatement(
                "DELETE FROM app_properties WHERE workspace_id = ? AND namespace = ?")) {
            delete.setString(1, workspaceId);
            delete.setString(2, namespace);
            delete.executeUpdate();
        }
        String sql = """
                INSERT INTO app_properties(workspace_id, namespace, prop_key, prop_value, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (String key : props.stringPropertyNames()) {
                ps.setString(1, workspaceId);
                ps.setString(2, namespace);
                ps.setString(3, key);
                ps.setString(4, props.getProperty(key));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public static Properties loadNamespace(String namespace) {
        Properties props = new Properties();
        String sql = """
                SELECT prop_key, prop_value
                FROM app_properties
                WHERE workspace_id = ? AND namespace = ?
                ORDER BY prop_key
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            ps.setString(2, namespace);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String value = rs.getString("prop_value");
                    props.setProperty(rs.getString("prop_key"), value == null ? "" : value);
                }
            }
        } catch (SQLException e) {
            log.error("读取 H2 配置失败: namespace={}", namespace, e);
        }
        return props;
    }

}
