package com.javaclaw.agent.expert;

import com.javaclaw.config.AppDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义智能体配置管理器
 *
 * <p>管理用户自定义智能体定义的增删改查，持久化到全局 H2 数据库的
 * {@code custom_agents} 表，并按 {@code workspace_id} 隔离；启动时只从 H2 读取。</p>
 *
 * <p>自定义智能体为纯推理型（无内置工具），可配置名称、描述、系统提示词和最大迭代次数。</p>
 *
 * @author JavaClaw
 */
public class CustomAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(CustomAgentConfig.class);

    private static CustomAgentConfig INSTANCE;

    /** 所有自定义智能体（id → 定义） */
    private final Map<String, CustomAgentDef> agents = new ConcurrentHashMap<>();

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

    private CustomAgentConfig() {
        load();
    }

    public static synchronized CustomAgentConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CustomAgentConfig();
        }
        return INSTANCE;
    }

    /**
     * 重新加载（工作区切换时调用）
     */
    public void reload() {
        agents.clear();
        load();
    }

    // ==================== CRUD ====================

    public List<CustomAgentDef> getAll() {
        return new ArrayList<>(agents.values());
    }

    public List<CustomAgentDef> getEnabled() {
        return agents.values().stream()
                .filter(a -> a.enabled)
                .toList();
    }

    public CustomAgentDef get(String id) {
        return agents.get(id);
    }

    public CustomAgentDef create(String name) {
        CustomAgentDef def = new CustomAgentDef(name);
        agents.put(def.id, def);
        save();
        log.info("创建自定义智能体: {} ({})", name, def.id);
        return def;
    }

    public void update(CustomAgentDef def) {
        agents.put(def.id, def);
        save();
        log.info("更新自定义智能体: {} ({})", def.name, def.id);
    }

    public void delete(String id) {
        CustomAgentDef removed = agents.remove(id);
        if (removed != null) {
            save();
            log.info("删除自定义智能体: {} ({})", removed.name, id);
        }
    }

    // ==================== 持久化 ====================

    private void load() {
        String sql = """
                SELECT id, name, tool_name, description, sys_prompt, max_iters, enabled
                FROM custom_agents
                WHERE workspace_id = ?
                ORDER BY name, id
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CustomAgentDef def = new CustomAgentDef();
                    def.id = rs.getString("id");
                    def.name = rs.getString("name");
                    def.toolName = rs.getString("tool_name");
                    def.description = rs.getString("description");
                    def.sysPrompt = rs.getString("sys_prompt");
                    def.maxIters = rs.getInt("max_iters");
                    def.enabled = rs.getBoolean("enabled");
                    if (def.id != null) agents.put(def.id, def);
                }
            }
            log.info("已从 H2 加载 {} 个自定义智能体", agents.size());
        } catch (SQLException e) {
            log.error("从 H2 加载自定义智能体配置失败", e);
        }
    }

    private void save() {
        String insert = """
                INSERT INTO custom_agents(
                    workspace_id, id, name, tool_name, description, sys_prompt, max_iters, enabled, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement del = c.prepareStatement("DELETE FROM custom_agents WHERE workspace_id = ?");
             PreparedStatement ps = c.prepareStatement(insert)) {
            c.setAutoCommit(false);
            String workspaceId = AppDatabase.currentWorkspaceId();
            del.setString(1, workspaceId);
            del.executeUpdate();
            for (CustomAgentDef def : agents.values()) {
                ps.setString(1, workspaceId);
                ps.setString(2, def.id);
                ps.setString(3, def.name);
                ps.setString(4, def.toolName);
                ps.setString(5, def.description);
                ps.setString(6, def.sysPrompt);
                ps.setInt(7, def.maxIters);
                ps.setBoolean(8, def.enabled);
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
            log.info("自定义智能体配置已保存到 H2，共 {} 个", agents.size());
        } catch (SQLException e) {
            log.error("保存自定义智能体配置到 H2 失败", e);
        }
    }

}
