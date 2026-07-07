package com.javaclaw.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.config.AppDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * MCP 服务器配置管理器
 *
 * <p>管理 MCP 服务器配置的增删改查，持久化到全局 H2 数据库的
 * {@code mcp_servers} 表，并按 {@code workspace_id} 隔离；启动时只从 H2 读取。</p>
 *
 * @author JavaClaw
 */
public class McpConfigManager {

    private static final Logger log = LoggerFactory.getLogger(McpConfigManager.class);

    /** 单例 */
    private static McpConfigManager INSTANCE;

    private final ObjectMapper objectMapper;

    /** 服务器配置列表（名称 → 配置） */
    private final Map<String, McpServerConfig> servers = new LinkedHashMap<>();

    private McpConfigManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // 对历史 JSON 中的派生字段（如 "transport"）保持兼容：宽容未知字段，避免整份配置加载失败
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        load();
    }

    public static synchronized McpConfigManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new McpConfigManager();
        }
        return INSTANCE;
    }

    /**
     * 从 H2 加载配置。
     */
    public void load() {
        servers.clear();
        String sql = """
                SELECT name, command, args_json, env_json, url, headers_json, enabled
                FROM mcp_servers
                WHERE workspace_id = ?
                ORDER BY name
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    McpServerConfig config = new McpServerConfig();
                    config.setName(rs.getString("name"));
                    config.setCommand(rs.getString("command"));
                    config.setArgs(readStringList(rs.getString("args_json")));
                    config.setEnv(readStringMap(rs.getString("env_json")));
                    config.setUrl(rs.getString("url"));
                    config.setHeaders(readStringMap(rs.getString("headers_json")));
                    config.setEnabled(rs.getBoolean("enabled"));
                    servers.put(config.getName(), config);
                }
            }
            log.info("MCP 配置已从 H2 加载，共 {} 个服务器", servers.size());
        } catch (SQLException e) {
            log.error("从 H2 加载 MCP 配置失败", e);
        }

    }

    /**
     * 保存配置到 H2
     */
    public void save() {
        String insert = """
                INSERT INTO mcp_servers(
                    workspace_id, name, command, args_json, env_json, url, headers_json, enabled, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement del = c.prepareStatement("DELETE FROM mcp_servers WHERE workspace_id = ?");
             PreparedStatement ps = c.prepareStatement(insert)) {
            c.setAutoCommit(false);
            String workspaceId = AppDatabase.currentWorkspaceId();
            del.setString(1, workspaceId);
            del.executeUpdate();
            for (McpServerConfig config : servers.values()) {
                ps.setString(1, workspaceId);
                ps.setString(2, config.getName());
                ps.setString(3, config.getCommand());
                ps.setString(4, objectMapper.writeValueAsString(config.getArgs()));
                ps.setString(5, objectMapper.writeValueAsString(config.getEnv()));
                ps.setString(6, config.getUrl());
                ps.setString(7, objectMapper.writeValueAsString(config.getHeaders()));
                ps.setBoolean(8, config.isEnabled());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
            log.info("MCP 配置已保存到 H2: {}", AppDatabase.databaseDisplayPath());
        } catch (SQLException | IOException e) {
            log.error("保存 MCP 配置到 H2 失败", e);
        }
    }

    /**
     * 重新加载配置（工作区切换时调用）
     */
    public void reload() {
        load();
    }

    /**
     * 获取所有服务器配置
     */
    public List<McpServerConfig> getAllServers() {
        return new ArrayList<>(servers.values());
    }

    /**
     * 获取所有启用的服务器配置
     */
    public List<McpServerConfig> getEnabledServers() {
        return servers.values().stream()
                .filter(McpServerConfig::isEnabled)
                .toList();
    }

    /**
     * 根据名称获取服务器配置
     */
    public McpServerConfig getServer(String name) {
        return servers.get(name);
    }

    /**
     * 添加或更新服务器配置
     */
    public void putServer(McpServerConfig config) {
        servers.put(config.getName(), config);
        save();
        log.info("MCP 服务器配置已更新: {}", config.getName());
    }

    /**
     * 删除服务器配置
     */
    public void removeServer(String name) {
        if (servers.remove(name) != null) {
            save();
            log.info("MCP 服务器配置已删除: {}", name);
        }
    }

    /**
     * 是否有启用的 MCP 服务器
     */
    public boolean hasEnabledServers() {
        return servers.values().stream().anyMatch(McpServerConfig::isEnabled);
    }

    /**
     * 获取配置文件路径（用于界面显示）
     */
    public String getConfigFilePath() {
        return AppDatabase.databaseDisplayPath();
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (IOException e) {
            log.warn("解析 MCP 参数列表失败，使用空列表", e);
            return List.of();
        }
    }

    private Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            log.warn("解析 MCP Map 配置失败，使用空 Map", e);
            return Map.of();
        }
    }
}
