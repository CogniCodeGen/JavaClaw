package com.javaclaw.plugin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.config.AppDatabase;
import com.javaclaw.plugin.api.Capability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 插件状态持久化（工作区维度）—— 记录每个插件的"是否启用"与"已授权能力"，落在当前工作区
 * H2 数据库的 {@code plugin_state} 表。供下次启动自动恢复已启用插件、跳过已授权能力的重复确认。
 *
 * <p>插件本体 jar 是全局的（{@code {user.dir}/plugins/}），但启用态与授权按工作区隔离并存入 H2。</p>
 *
 * @author JavaClaw
 */
final class PluginStore {

    private static final Logger log = LoggerFactory.getLogger(PluginStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单插件的持久化条目（公开字段，便于 Jackson 读写） */
    static final class Persist {
        public boolean enabled;
        public List<String> granted = new ArrayList<>();
        /** 插件自有配置（secret 项以密文存储，由 PluginManager 加解密） */
        public Map<String, String> config = new LinkedHashMap<>();
    }

    private final Map<String, Persist> entries = new LinkedHashMap<>();

    /** （重新）绑定到当前工作区并从 H2 加载。 */
    void bind(Path dataRoot) {
        entries.clear();
        load();
    }

    boolean isEnabled(String id) {
        Persist p = entries.get(id);
        return p != null && p.enabled;
    }

    /** 读取已授权能力集合（未知能力名跳过）。 */
    Set<Capability> granted(String id) {
        Persist p = entries.get(id);
        Set<Capability> result = new LinkedHashSet<>();
        if (p != null) {
            for (String name : p.granted) {
                try {
                    result.add(Capability.valueOf(name.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // 旧版本遗留的未知能力名，忽略
                }
            }
        }
        return result;
    }

    /** 记录启用态与授权能力并持久化。 */
    void update(String id, boolean enabled, Set<Capability> granted) {
        Persist p = entries.computeIfAbsent(id, k -> new Persist());
        p.enabled = enabled;
        p.granted = granted.stream().map(Enum::name).toList();
        save();
    }

    /** 仅更新启用态（保留已授权能力）。 */
    void setEnabled(String id, boolean enabled) {
        Persist p = entries.computeIfAbsent(id, k -> new Persist());
        p.enabled = enabled;
        save();
    }

    /** 读取插件配置（原样返回，secret 项为密文，由 PluginManager 解密）。 */
    Map<String, String> config(String id) {
        Persist p = entries.get(id);
        return p == null ? new LinkedHashMap<>() : new LinkedHashMap<>(p.config);
    }

    /** 写入插件配置（secret 项应已由 PluginManager 加密）并持久化。 */
    void setConfig(String id, Map<String, String> config) {
        Persist p = entries.computeIfAbsent(id, k -> new Persist());
        p.config = new LinkedHashMap<>(config);
        save();
    }

    private void load() {
        String sql = """
                SELECT plugin_id, enabled, granted_json, config_json
                FROM plugin_state
                WHERE workspace_id = ?
                ORDER BY plugin_id
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Persist p = new Persist();
                    p.enabled = rs.getBoolean("enabled");
                    p.granted = readStringList(rs.getString("granted_json"));
                    p.config = readStringMap(rs.getString("config_json"));
                    entries.put(rs.getString("plugin_id"), p);
                }
            }
            log.info("插件状态已从 H2 加载：{} 条", entries.size());
        } catch (SQLException e) {
            log.warn("从 H2 加载插件状态失败，使用空状态：{}", e.toString());
        }
    }

    private void save() {
        String insert = """
                INSERT INTO plugin_state(workspace_id, plugin_id, enabled, granted_json, config_json, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement del = c.prepareStatement("DELETE FROM plugin_state WHERE workspace_id = ?");
             PreparedStatement ps = c.prepareStatement(insert)) {
            c.setAutoCommit(false);
            String workspaceId = AppDatabase.currentWorkspaceId();
            del.setString(1, workspaceId);
            del.executeUpdate();
            for (Map.Entry<String, Persist> e : entries.entrySet()) {
                ps.setString(1, workspaceId);
                ps.setString(2, e.getKey());
                ps.setBoolean(3, e.getValue().enabled);
                ps.setString(4, MAPPER.writeValueAsString(e.getValue().granted));
                ps.setString(5, MAPPER.writeValueAsString(e.getValue().config));
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
        } catch (SQLException | IOException e) {
            log.error("保存插件状态到 H2 失败：{}", e.toString());
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> out = MAPPER.readValue(json, new TypeReference<>() {});
            return out != null ? out : new ArrayList<>();
        } catch (IOException e) {
            log.warn("解析插件授权列表失败：{}", e.toString());
            return new ArrayList<>();
        }
    }

    private Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, String> out = MAPPER.readValue(json, new TypeReference<>() {});
            return out != null ? out : new LinkedHashMap<>();
        } catch (IOException e) {
            log.warn("解析插件配置失败：{}", e.toString());
            return new LinkedHashMap<>();
        }
    }
}
