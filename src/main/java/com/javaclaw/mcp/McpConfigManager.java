package com.javaclaw.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.config.WorkspaceManager;
import com.javaclaw.util.AtomicFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * MCP 服务器配置管理器
 *
 * <p>管理 MCP 服务器配置的增删改查，持久化到工作区的
 * {@code data/mcp-servers.json} 文件。</p>
 *
 * @author JavaClaw
 */
public class McpConfigManager {

    private static final Logger log = LoggerFactory.getLogger(McpConfigManager.class);

    /** 配置文件名 */
    private static final String CONFIG_FILE_NAME = "data/mcp-servers.json";

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
     * 获取配置文件路径
     */
    private Path getConfigPath() {
        return WorkspaceManager.getInstance()
                .getCurrentWorkspacePath().resolve(CONFIG_FILE_NAME);
    }

    /**
     * 从文件加载配置
     */
    public void load() {
        servers.clear();
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try {
                List<McpServerConfig> list = objectMapper.readValue(
                        path.toFile(), new TypeReference<List<McpServerConfig>>() {});
                for (McpServerConfig config : list) {
                    servers.put(config.getName(), config);
                }
                log.info("MCP 配置已加载，共 {} 个服务器", servers.size());
            } catch (IOException e) {
                log.error("加载 MCP 配置失败（文件存在但解析出错），路径: {}，错误: {}",
                        path, e.getMessage(), e);
            }
        } else {
            log.info("MCP 配置文件不存在，使用空配置: {}", path);
        }
    }

    /**
     * 保存配置到文件
     */
    public void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            AtomicFileWriter.writeJson(objectMapper, path.toFile(), new ArrayList<>(servers.values()));
            log.info("MCP 配置已保存: {}", path);
        } catch (IOException e) {
            log.error("保存 MCP 配置失败", e);
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
        return getConfigPath().toString();
    }
}
