package com.javaclaw.agent.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.config.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义智能体配置管理器
 *
 * <p>管理用户自定义智能体定义的增删改查和 JSON 持久化。
 * 数据存储在当前工作区 {@code data/custom-agents.json}。</p>
 *
 * <p>自定义智能体为纯推理型（无内置工具），可配置名称、描述、系统提示词和最大迭代次数。</p>
 *
 * @author JavaClaw
 */
public class CustomAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(CustomAgentConfig.class);

    private static final String CONFIG_FILE = "custom-agents.json";

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

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

    private Path getConfigFile() {
        Path workspacePath = WorkspaceManager.getInstance().getCurrentWorkspacePath();
        return workspacePath.resolve("data").resolve(CONFIG_FILE);
    }

    private void load() {
        Path file = getConfigFile();
        if (!Files.exists(file)) {
            log.info("无自定义智能体配置文件，使用空列表");
            return;
        }

        try {
            CustomAgentDef[] defs = JSON.readValue(file.toFile(), CustomAgentDef[].class);
            for (CustomAgentDef def : defs) {
                if (def.id != null) {
                    agents.put(def.id, def);
                }
            }
            log.info("已加载 {} 个自定义智能体", agents.size());
        } catch (IOException e) {
            log.error("加载自定义智能体配置失败", e);
        }
    }

    private void save() {
        Path file = getConfigFile();
        try {
            Files.createDirectories(file.getParent());
            JSON.writeValue(file.toFile(), agents.values());
            log.info("自定义智能体配置已保存，共 {} 个", agents.size());
        } catch (IOException e) {
            log.error("保存自定义智能体配置失败", e);
        }
    }
}
