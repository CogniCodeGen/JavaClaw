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
        String sql = """
                MERGE INTO app_properties(workspace_id, namespace, prop_key, prop_value, updated_at)
                KEY(workspace_id, namespace, prop_key)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            String workspaceId = AppDatabase.currentWorkspaceId();
            for (String key : props.stringPropertyNames()) {
                ps.setString(1, workspaceId);
                ps.setString(2, namespace);
                ps.setString(3, key);
                ps.setString(4, props.getProperty(key));
                ps.addBatch();
            }
            ps.executeBatch();
            return true;
        } catch (SQLException e) {
            log.error("保存 H2 配置失败: namespace={}", namespace, e);
            return false;
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
