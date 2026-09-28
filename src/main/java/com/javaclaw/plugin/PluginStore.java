package com.javaclaw.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.plugin.api.Capability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工作区插件状态仓储。
 *
 * <p>实例由根 Spring Context 管理，但每次只绑定一个明确的工作区快照。切换工作区时
 * {@link #bind(String)} 会完整替换内存状态；后续写入始终使用该快照，不读取可变的全局
 * 工作区指针。所有操作线程安全，写操作在单个 Spring 事务内提交。</p>
 */
public final class PluginStore {

    private static final Logger log = LoggerFactory.getLogger(PluginStore.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final Map<String, Persist> entries = new LinkedHashMap<>();
    private String workspaceId;

    public PluginStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(json, "json");
    }

    /** 绑定并加载一个工作区；加载失败时不发布部分状态。 */
    synchronized void bind(String workspaceId) {
        String checkedId = requireWorkspaceId(workspaceId);
        Map<String, Persist> loaded = load(checkedId);
        this.workspaceId = checkedId;
        entries.clear();
        entries.putAll(loaded);
        log.info("工作区 {} 的插件状态已加载：{} 条", checkedId, entries.size());
    }

    synchronized boolean isEnabled(String id) {
        Persist persisted = entries.get(id);
        return persisted != null && persisted.enabled;
    }

    /** 读取已授权能力集合。 */
    synchronized Set<Capability> granted(String id) {
        Persist persisted = entries.get(id);
        Set<Capability> result = new LinkedHashSet<>();
        if (persisted == null) {
            return result;
        }
        for (String name : persisted.granted) {
            result.add(Capability.valueOf(name));
        }
        return result;
    }

    synchronized void update(String id, boolean enabled, Set<Capability> granted) {
        Map<String, Persist> next = copyEntries();
        Persist persisted = next.computeIfAbsent(id, ignored -> new Persist());
        persisted.enabled = enabled;
        persisted.granted = granted.stream().map(Enum::name).toList();
        replaceAfterCommit(next);
    }

    synchronized void setEnabled(String id, boolean enabled) {
        Map<String, Persist> next = copyEntries();
        Persist persisted = next.computeIfAbsent(id, ignored -> new Persist());
        persisted.enabled = enabled;
        replaceAfterCommit(next);
    }

    synchronized Map<String, String> config(String id) {
        Persist persisted = entries.get(id);
        return persisted == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(persisted.config);
    }

    synchronized void setConfig(String id, Map<String, String> config) {
        Map<String, Persist> next = copyEntries();
        Persist persisted = next.computeIfAbsent(id, ignored -> new Persist());
        persisted.config = new LinkedHashMap<>(config);
        replaceAfterCommit(next);
    }

    private Map<String, Persist> load(String workspaceId) {
        String sql = """
                SELECT plugin_id, enabled, granted_json, config_json
                FROM plugin_state
                WHERE workspace_id = ?
                ORDER BY plugin_id
                """;
        Map<String, Persist> loaded = new LinkedHashMap<>();
        jdbc.query(sql, result -> {
            Persist persisted = new Persist();
            persisted.enabled = result.getBoolean("enabled");
            persisted.granted = readStringList(result.getString("granted_json"));
            persisted.config = readStringMap(result.getString("config_json"));
            loaded.put(result.getString("plugin_id"), persisted);
        }, workspaceId);
        return loaded;
    }

    /**
     * 以工作区快照替换持久状态。先完成 JSON 编码再开启事务，编码失败不会触碰数据库。
     */
    private void replaceAfterCommit(Map<String, Persist> next) {
        String boundWorkspaceId = requireWorkspaceId(workspaceId);
        List<Object[]> rows = encodeRows(boundWorkspaceId, next);
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM plugin_state WHERE workspace_id = ?", boundWorkspaceId);
            if (!rows.isEmpty()) {
                jdbc.batchUpdate("""
                        INSERT INTO plugin_state(
                            workspace_id, plugin_id, enabled, granted_json, config_json, updated_at)
                        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, rows);
            }
        });
        entries.clear();
        entries.putAll(next);
    }

    private List<Object[]> encodeRows(
            String boundWorkspaceId, Map<String, Persist> snapshot) {
        List<Object[]> rows = new ArrayList<>(snapshot.size());
        try {
            for (Map.Entry<String, Persist> entry : snapshot.entrySet()) {
                Persist persisted = entry.getValue();
                rows.add(new Object[]{
                        boundWorkspaceId,
                        entry.getKey(),
                        persisted.enabled,
                        json.writeValueAsString(persisted.granted),
                        json.writeValueAsString(persisted.config)
                });
            }
            return rows;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("插件状态无法编码为 JSON", failure);
        }
    }

    private Map<String, Persist> copyEntries() {
        Map<String, Persist> copy = new LinkedHashMap<>();
        entries.forEach((id, persisted) -> copy.put(id, persisted.copy()));
        return copy;
    }

    private List<String> readStringList(String value) {
        JsonNode parsed = readPersistedJson(value, "granted_json");
        if (!parsed.isArray()) throw new IllegalStateException("插件授权列表必须是 JSON 数组");
        List<String> names = new ArrayList<>();
        for (JsonNode item : parsed) {
            if (!item.isTextual()) throw new IllegalStateException("插件授权名称必须是字符串");
            String name = item.textValue();
            try {
                Capability.valueOf(name);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("未知的插件授权能力: " + name, invalid);
            }
            names.add(name);
        }
        return names;
    }

    private Map<String, String> readStringMap(String value) {
        JsonNode parsed = readPersistedJson(value, "config_json");
        if (!parsed.isObject()) throw new IllegalStateException("插件配置必须是 JSON 对象");
        Map<String, String> config = new LinkedHashMap<>();
        parsed.fields().forEachRemaining(field -> {
            if (!field.getValue().isTextual()) {
                throw new IllegalStateException("插件配置值必须是字符串: " + field.getKey());
            }
            config.put(field.getKey(), field.getValue().textValue());
        });
        return config;
    }

    private JsonNode readPersistedJson(String value, String column) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("插件状态缺少 " + column);
        }
        try {
            JsonNode parsed = json.readTree(value);
            if (parsed == null) throw new IllegalStateException("插件状态缺少 " + column);
            return parsed;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("插件状态 JSON 损坏: " + column, failure);
        }
    }

    private static String requireWorkspaceId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("插件状态仓储尚未绑定工作区");
        }
        return value;
    }

    private static final class Persist {
        private boolean enabled;
        private List<String> granted = new ArrayList<>();
        private Map<String, String> config = new LinkedHashMap<>();

        private Persist copy() {
            Persist copy = new Persist();
            copy.enabled = enabled;
            copy.granted = new ArrayList<>(granted);
            copy.config = new LinkedHashMap<>(config);
            return copy;
        }
    }
}
