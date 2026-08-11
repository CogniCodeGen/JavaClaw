package com.javaclaw.agent.expert;

import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作区范围的自定义智能体存储。
 *
 * <p>实例由工作区 Spring Context 管理，不能跨工作区或在 Context 关闭后复用。写操作先提交
 * H2 再更新缓存，并在实例内串行化，查询返回防御性副本，因此可被多个虚拟线程安全调用。
 * 本类不拥有 DataSource，销毁时无需单独释放资源。</p>
 */
public final class CustomAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(CustomAgentConfig.class);

    /** 所有自定义智能体（id → 定义） */
    private final Map<String, CustomAgentDef> agents = new ConcurrentHashMap<>();
    private final String workspaceId;
    private final JdbcTemplate jdbc;

    /**
     * 自定义智能体定义
     */
    public static class CustomAgentDef {
        /** 唯一标识（UUID） */
        public String id;
        /** 智能体名称（显示名） */
        public String name;
        /** 工具名称（SubAgentTool 注册名，英文+下划线） */
        public String toolName;
        /** 智能体描述（告诉编排器何时调用此智能体） */
        public String description;
        /** 系统提示词 */
        public String sysPrompt;
        /** 最大迭代次数 */
        public int maxIters = 1;
        /** 是否启用 */
        public boolean enabled = true;

        public CustomAgentDef() {}

        public CustomAgentDef(String name) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.name = name;
            this.toolName = "custom_" + id;
            this.description = "";
            this.sysPrompt = "";
            this.maxIters = 1;
            this.enabled = true;
        }
    }

    public CustomAgentConfig(String workspaceId, JdbcTemplate jdbc) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        this.workspaceId = workspaceId;
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        load();
    }

    public List<CustomAgentDef> getAll() {
        return agents.values().stream()
                .map(CustomAgentConfig::copy)
                .sorted(Comparator.<CustomAgentDef, String>comparing(
                        def -> def.name == null ? "" : def.name,
                        String.CASE_INSENSITIVE_ORDER).thenComparing(def -> def.id))
                .toList();
    }

    public List<CustomAgentDef> getEnabled() {
        return getAll().stream()
                .filter(a -> a.enabled)
                .toList();
    }

    public CustomAgentDef get(String id) {
        return copy(agents.get(id));
    }

    public synchronized CustomAgentDef create(String name) {
        CustomAgentDef def = new CustomAgentDef(name);
        jdbc.update("""
                        INSERT INTO custom_agents(
                            workspace_id, id, name, tool_name, description, sys_prompt,
                            max_iters, enabled, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                workspaceId, def.id, def.name, def.toolName, def.description,
                def.sysPrompt, def.maxIters, def.enabled);
        agents.put(def.id, copy(def));
        log.info("创建自定义智能体: {} ({})", name, def.id);
        return copy(def);
    }

    public synchronized void update(CustomAgentDef def) {
        Objects.requireNonNull(def, "def");
        int updated = jdbc.update("""
                        UPDATE custom_agents
                        SET name = ?, tool_name = ?, description = ?, sys_prompt = ?,
                            max_iters = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE workspace_id = ? AND id = ?
                        """,
                def.name, def.toolName, def.description, def.sysPrompt,
                def.maxIters, def.enabled, workspaceId, def.id);
        if (updated == 0) {
            throw new IllegalArgumentException("未找到自定义智能体: " + def.id);
        }
        agents.put(def.id, copy(def));
        log.info("更新自定义智能体: {} ({})", def.name, def.id);
    }

    public synchronized void delete(String id) {
        int deleted = jdbc.update(
                "DELETE FROM custom_agents WHERE workspace_id = ? AND id = ?",
                workspaceId, id);
        CustomAgentDef removed = agents.remove(id);
        if (deleted > 0 && removed != null) {
            log.info("删除自定义智能体: {} ({})", removed.name, id);
        }
    }

    private void load() {
        String sql = """
                SELECT id, name, tool_name, description, sys_prompt, max_iters, enabled
                FROM custom_agents
                WHERE workspace_id = ?
                ORDER BY name, id
                """;
        jdbc.query(sql, (rs, rowNumber) -> {
            CustomAgentDef def = new CustomAgentDef();
            def.id = rs.getString("id");
            def.name = rs.getString("name");
            def.toolName = rs.getString("tool_name");
            def.description = rs.getString("description");
            def.sysPrompt = rs.getString("sys_prompt");
            def.maxIters = rs.getInt("max_iters");
            def.enabled = rs.getBoolean("enabled");
            return def;
        }, workspaceId).forEach(def -> agents.put(def.id, def));
        log.info("已从 H2 加载 {} 个自定义智能体", agents.size());
    }

    private static CustomAgentDef copy(CustomAgentDef source) {
        if (source == null) return null;
        CustomAgentDef copy = new CustomAgentDef();
        copy.id = source.id;
        copy.name = source.name;
        copy.toolName = source.toolName;
        copy.description = source.description;
        copy.sysPrompt = source.sysPrompt;
        copy.maxIters = source.maxIters;
        copy.enabled = source.enabled;
        return copy;
    }

}
