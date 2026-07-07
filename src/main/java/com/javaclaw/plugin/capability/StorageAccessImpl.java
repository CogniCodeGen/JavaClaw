package com.javaclaw.plugin.capability;

import com.javaclaw.config.AppDatabase;
import com.javaclaw.plugin.CapabilityGuard;
import com.javaclaw.plugin.api.Capability;
import com.javaclaw.plugin.api.capability.StorageAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/**
 * STORAGE 能力实现 —— 托管键值存储，落在全局 H2 {@code plugin_storage} 表，
 * 按工作区隔离。插件无须关心路径，写入即持久化。
 *
 * @author JavaClaw
 */
public final class StorageAccessImpl implements StorageAccess {

    private static final Logger log = LoggerFactory.getLogger(StorageAccessImpl.class);

    private final String pluginId;
    private final Properties props = new Properties();

    public StorageAccessImpl(String pluginId, Path dataRoot) {
        this.pluginId = pluginId;
        load();
    }

    @Override
    public synchronized String get(String key) {
        CapabilityGuard.require(Capability.STORAGE);
        return props.getProperty(key, "");
    }

    @Override
    public synchronized void put(String key, String value) {
        CapabilityGuard.require(Capability.STORAGE);
        props.setProperty(key, value == null ? "" : value);
        save();
    }

    @Override
    public synchronized void remove(String key) {
        CapabilityGuard.require(Capability.STORAGE);
        props.remove(key);
        save();
    }

    @Override
    public synchronized Set<String> keys() {
        CapabilityGuard.require(Capability.STORAGE);
        return new LinkedHashSet<>(props.stringPropertyNames());
    }

    private void load() {
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT store_key, store_value FROM plugin_storage WHERE workspace_id = ? AND plugin_id = ?")) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            ps.setString(2, pluginId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    props.setProperty(rs.getString("store_key"), rs.getString("store_value"));
                }
            }
        } catch (Exception e) {
            log.warn("插件[{}]存储从 H2 加载失败：{}", pluginId, e.toString());
        }
    }

    private void save() {
        String insert = """
                INSERT INTO plugin_storage(workspace_id, plugin_id, store_key, store_value, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement del = c.prepareStatement("DELETE FROM plugin_storage WHERE workspace_id = ? AND plugin_id = ?");
             PreparedStatement ps = c.prepareStatement(insert)) {
            c.setAutoCommit(false);
            String workspaceId = AppDatabase.currentWorkspaceId();
            del.setString(1, workspaceId);
            del.setString(2, pluginId);
            del.executeUpdate();
            for (String key : props.stringPropertyNames()) {
                ps.setString(1, workspaceId);
                ps.setString(2, pluginId);
                ps.setString(3, key);
                ps.setString(4, props.getProperty(key));
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (Exception e) {
            log.error("插件[{}]存储保存失败：{}", pluginId, e.toString());
        }
    }
}
