package com.javaclaw.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.config.CredentialEncryptor;
import com.javaclaw.config.DatabaseAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * MCP 服务器配置管理器
 *
 * <p>管理 MCP 服务器配置的增删改查，持久化到全局 H2 数据库的
 * {@code mcp_servers} 表，并按 {@code workspace_id} 隔离；启动时只从 H2 读取。</p>
 *
 * @author JavaClaw
 */
public final class McpConfigManager {

    private static final Logger log = LoggerFactory.getLogger(McpConfigManager.class);

    private final ObjectMapper objectMapper;
    private final DatabaseAccess databaseAccess;
    private final Supplier<String> workspaceIdSupplier;
    private final UnaryOperator<String> encryptor;
    private final UnaryOperator<String> decryptor;

    /** 服务器配置列表（名称 → 配置） */
    private final Map<String, McpServerConfig> servers = new LinkedHashMap<>();
    /** 内存配置快照所属工作区；写操作只使用该快照，禁止运行中重读可变全局值。 */
    private String loadedWorkspaceId;

    /**
     * 创建绑定到单一工作区的配置仓储。
     *
     * <p>实例由工作区 Spring Context 管理，不读取运行期可变的全局工作区状态；Context
     * 关闭后实例应一并丢弃。所有返回值都是当前工作区快照，写操作在数据库提交成功后才
     * 更新内存。该类型线程安全，但数据库操作仍应通过托管 I/O 执行器调用。</p>
     */
    public McpConfigManager(DatabaseAccess databaseAccess, String workspaceId) {
        this(databaseAccess, () -> Objects.requireNonNull(workspaceId, "workspaceId"),
                CredentialEncryptor::encrypt, CredentialEncryptor::decrypt);
    }

    McpConfigManager(DatabaseAccess databaseAccess,
                     Supplier<String> workspaceIdSupplier,
                     UnaryOperator<String> encryptor,
                     UnaryOperator<String> decryptor) {
        this.databaseAccess = Objects.requireNonNull(databaseAccess, "databaseAccess");
        this.workspaceIdSupplier = Objects.requireNonNull(workspaceIdSupplier, "workspaceIdSupplier");
        this.encryptor = Objects.requireNonNull(encryptor, "encryptor");
        this.decryptor = Objects.requireNonNull(decryptor, "decryptor");
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // 对历史 JSON 中的派生字段（如 "transport"）保持兼容：宽容未知字段，避免整份配置加载失败
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        load();
    }

    /**
     * 从 H2 加载配置。
     */
    public synchronized void load() {
        String workspaceId = workspaceIdSupplier.get();
        Map<String, McpServerConfig> loaded = new LinkedHashMap<>();
        boolean migrationNeeded = false;
        String sql = """
                SELECT name, command, args_json, env_json, url, headers_json, enabled
                FROM mcp_servers
                WHERE workspace_id = ?
                ORDER BY name
                """;
        try (Connection c = databaseAccess.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workspaceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    McpServerConfig config = new McpServerConfig();
                    config.setName(rs.getString("name"));
                    config.setCommand(rs.getString("command"));
                    String argsStored = rs.getString("args_json");
                    String envStored = rs.getString("env_json");
                    String urlStored = rs.getString("url");
                    String headersStored = rs.getString("headers_json");
                    migrationNeeded |= needsEncryption(argsStored) || needsEncryption(envStored)
                            || needsEncryption(urlStored) || needsEncryption(headersStored);
                    config.setArgs(readStringList(decryptRequired(argsStored)));
                    config.setEnv(readStringMap(decryptRequired(envStored)));
                    config.setUrl(decryptRequired(urlStored));
                    config.setHeaders(readStringMap(decryptRequired(headersStored)));
                    config.setEnabled(rs.getBoolean("enabled"));
                    loaded.put(config.getName(), config);
                }
            }
            if (migrationNeeded) {
                rewriteEncryptedSnapshot(c, workspaceId, loaded.values());
                log.info("已把工作区 {} 的旧 MCP 明文配置迁移为加密存储", workspaceId);
            }
            servers.clear();
            servers.putAll(loaded);
            loadedWorkspaceId = workspaceId;
            log.info("MCP 配置已从 H2 加载，共 {} 个服务器", servers.size());
        } catch (SQLException | RuntimeException e) {
            log.error("从 H2 加载 MCP 配置失败", e);
            throw new IllegalStateException("MCP 配置加载或加密迁移失败", e);
        }

    }

    /**
     * 保存配置到 H2
     */
    public synchronized void save() {
        String workspaceId = requireLoadedWorkspace();
        String insert = """
                INSERT INTO mcp_servers(
                    workspace_id, name, command, args_json, env_json, url, headers_json, enabled, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = databaseAccess.open();
             PreparedStatement del = c.prepareStatement("DELETE FROM mcp_servers WHERE workspace_id = ?");
             PreparedStatement ps = c.prepareStatement(insert)) {
            c.setAutoCommit(false);
            del.setString(1, workspaceId);
            del.executeUpdate();
            for (McpServerConfig config : servers.values()) {
                ps.setString(1, workspaceId);
                ps.setString(2, config.getName());
                ps.setString(3, config.getCommand());
                ps.setString(4, encryptor.apply(objectMapper.writeValueAsString(config.getArgs())));
                ps.setString(5, encryptor.apply(objectMapper.writeValueAsString(config.getEnv())));
                ps.setString(6, encryptor.apply(config.getUrl()));
                ps.setString(7, encryptor.apply(objectMapper.writeValueAsString(config.getHeaders())));
                ps.setBoolean(8, config.isEnabled());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
            log.info("MCP 配置已保存到 H2: {}", databaseAccess.description());
        } catch (SQLException | IOException | RuntimeException e) {
            log.error("保存 MCP 配置到 H2 失败", e);
            throw new IllegalStateException("MCP 配置未能确认写入数据库", e);
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
    public synchronized List<McpServerConfig> getAllServers() {
        return servers.values().stream().map(McpConfigManager::copyOf).toList();
    }

    /**
     * 获取所有启用的服务器配置
     */
    public synchronized List<McpServerConfig> getEnabledServers() {
        return servers.values().stream()
                .filter(McpServerConfig::isEnabled)
                .map(McpConfigManager::copyOf)
                .toList();
    }

    /**
     * 根据名称获取服务器配置
     */
    public synchronized McpServerConfig getServer(String name) {
        McpServerConfig config = servers.get(name);
        return config == null ? null : copyOf(config);
    }

    /**
     * 添加或更新服务器配置
     */
    public synchronized void putServer(McpServerConfig config) {
        Objects.requireNonNull(config, "config");
        McpServerConfig stored = copyOf(config);
        McpServerConfig previous = servers.put(stored.getName(), stored);
        try {
            save();
        } catch (RuntimeException e) {
            if (previous == null) servers.remove(stored.getName());
            else servers.put(stored.getName(), previous);
            throw e;
        }
        log.info("MCP 服务器配置已更新: {}", stored.getName());
    }

    /**
     * 单条事务式写入；只有 H2 提交成功后才更新内存快照。
     *
     * <p>对话管理工具使用本入口，避免兼容方法 {@link #putServer(McpServerConfig)}
     * 吞掉保存异常后仍向模型返回“已创建”。</p>
     *
     * @throws IllegalStateException 序列化或数据库写入失败
     */
    public synchronized McpServerConfig putServerChecked(McpServerConfig config) {
        Objects.requireNonNull(config, "config");
        putServersChecked(List.of(config));
        return config;
    }

    /**
     * 在一个数据库事务中写入多条配置，提交成功后再整体更新内存快照。
     */
    public synchronized List<McpServerConfig> putServersChecked(
            Collection<McpServerConfig> configurations) {
        Objects.requireNonNull(configurations, "configurations");
        List<McpServerConfig> values = configurations.stream()
                .map(value -> copyOf(Objects.requireNonNull(value, "configuration")))
                .toList();
        if (values.isEmpty()) return values;
        String workspaceId = requireLoadedWorkspace();
        String upsert = """
                MERGE INTO mcp_servers(
                    workspace_id, name, command, args_json, env_json, url, headers_json, enabled, updated_at
                )
                KEY(workspace_id, name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = databaseAccess.open();
             PreparedStatement ps = c.prepareStatement(upsert)) {
            c.setAutoCommit(false);
            for (McpServerConfig config : values) {
                ps.setString(1, workspaceId);
                ps.setString(2, config.getName());
                ps.setString(3, config.getCommand());
                ps.setString(4, encryptor.apply(objectMapper.writeValueAsString(config.getArgs())));
                ps.setString(5, encryptor.apply(objectMapper.writeValueAsString(config.getEnv())));
                ps.setString(6, encryptor.apply(config.getUrl()));
                ps.setString(7, encryptor.apply(objectMapper.writeValueAsString(config.getHeaders())));
                ps.setBoolean(8, config.isEnabled());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
            values.forEach(config -> servers.put(config.getName(), copyOf(config)));
            log.info("已确认向 H2 写入 {} 条 MCP 服务器配置", values.size());
            return values;
        } catch (SQLException | IOException | RuntimeException e) {
            throw new IllegalStateException("MCP 配置写入 H2 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 删除服务器配置
     */
    public synchronized void removeServer(String name) {
        McpServerConfig removed = servers.remove(name);
        if (removed != null) {
            try {
                save();
            } catch (RuntimeException e) {
                servers.put(name, removed);
                throw e;
            }
            log.info("MCP 服务器配置已删除: {}", name);
        }
    }

    /** 事务式删除单条 MCP 配置；数据库提交成功后才更新内存快照。 */
    public synchronized boolean removeServerChecked(String name) {
        if (name == null || !servers.containsKey(name)) return false;
        String workspaceId = requireLoadedWorkspace();
        try (Connection c = databaseAccess.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM mcp_servers WHERE workspace_id = ? AND name = ?")) {
            c.setAutoCommit(false);
            ps.setString(1, workspaceId);
            ps.setString(2, name);
            if (ps.executeUpdate() != 1) {
                c.rollback();
                return false;
            }
            c.commit();
            servers.remove(name);
            log.info("MCP 服务器配置已确认从 H2 删除: {}", name);
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("删除 MCP 配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 是否有启用的 MCP 服务器
     */
    public synchronized boolean hasEnabledServers() {
        return servers.values().stream().anyMatch(McpServerConfig::isEnabled);
    }

    /**
     * 获取配置文件路径（用于界面显示）
     */
    public synchronized String getConfigFilePath() {
        return databaseAccess.description();
    }

    private static McpServerConfig copyOf(McpServerConfig source) {
        McpServerConfig copy = new McpServerConfig();
        copy.setName(source.getName());
        copy.setCommand(source.getCommand());
        copy.setArgs(List.copyOf(source.getArgs()));
        copy.setEnv(Map.copyOf(source.getEnv()));
        copy.setUrl(source.getUrl());
        copy.setHeaders(Map.copyOf(source.getHeaders()));
        copy.setEnabled(source.isEnabled());
        return copy;
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

    private boolean needsEncryption(String stored) {
        return stored != null && !stored.isBlank() && !CredentialEncryptor.isEncrypted(stored);
    }

    private String decryptRequired(String stored) {
        if (stored == null || stored.isBlank()) return stored;
        String plain = decryptor.apply(stored);
        if (CredentialEncryptor.isEncrypted(stored) && CredentialEncryptor.isEncrypted(plain)) {
            throw new IllegalStateException("MCP 加密配置无法解密，已拒绝加载");
        }
        return plain;
    }

    private void rewriteEncryptedSnapshot(Connection c, String workspaceId,
                                          Collection<McpServerConfig> configs) throws SQLException {
        String sql = """
                UPDATE mcp_servers
                SET args_json = ?, env_json = ?, url = ?, headers_json = ?, updated_at = CURRENT_TIMESTAMP
                WHERE workspace_id = ? AND name = ?
                """;
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (McpServerConfig config : configs) {
                    ps.setString(1, encryptor.apply(objectMapper.writeValueAsString(config.getArgs())));
                    ps.setString(2, encryptor.apply(objectMapper.writeValueAsString(config.getEnv())));
                    ps.setString(3, encryptor.apply(config.getUrl()));
                    ps.setString(4, encryptor.apply(objectMapper.writeValueAsString(config.getHeaders())));
                    ps.setString(5, workspaceId);
                    ps.setString(6, config.getName());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException | IOException | RuntimeException e) {
            try {
                c.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            if (e instanceof SQLException sqlException) throw sqlException;
            throw new SQLException("MCP 明文配置加密迁移失败", e);
        }
    }

    private String requireLoadedWorkspace() {
        String loaded = loadedWorkspaceId;
        String current = workspaceIdSupplier.get();
        if (loaded == null || loaded.isBlank() || !Objects.equals(loaded, current)) {
            throw new IllegalStateException("MCP 配置尚未绑定当前工作区，请先重新加载");
        }
        return loaded;
    }
}
