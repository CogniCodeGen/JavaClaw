package com.javaclaw.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 工作区管理器。
 *
 * <p>工作区索引、当前工作区、名称与创建时间均存储在根目录 {@code data/javaclaw.mv.db}
 * 的 {@code workspaces}/{@code app_state} 表中。少量文件资产放在根目录 {@code data/} 下并按
 * {@code workspace_id} 分桶。</p>
 */
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private static final String DEFAULT_WORKSPACE_NAME = "默认工作区";
    private static final String STATE_CURRENT_WORKSPACE = "current_workspace_id";

    private static WorkspaceManager instance;

    private final Path projectRoot;

    private final List<Workspace> workspaces = new ArrayList<>();
    private String currentWorkspaceId;

    /** 工作区切换回调 */
    private Consumer<Workspace> onWorkspaceSwitched;

    private WorkspaceManager() {
        this.projectRoot = Path.of(System.getProperty("user.dir"));
    }

    public static synchronized WorkspaceManager getInstance() {
        if (instance == null) {
            instance = new WorkspaceManager();
        }
        return instance;
    }

    public void init() {
        loadIndex();
        if (workspaces.isEmpty()) {
            Workspace defaultWs = new Workspace(DEFAULT_WORKSPACE_NAME);
            workspaces.add(defaultWs);
            currentWorkspaceId = defaultWs.getId();
            saveIndex();
            log.info("已创建默认工作区: {}", defaultWs.getId());
        }

        if (currentWorkspaceId == null || findById(currentWorkspaceId) == null) {
            currentWorkspaceId = workspaces.getFirst().getId();
            saveCurrentWorkspace();
        }

        updateLogDirProperty();
        log.info("工作区管理器已初始化，当前工作区: {} ({})",
                getCurrentWorkspace().getName(), currentWorkspaceId);
    }

    // ==================== 工作区操作 ====================

    public Workspace createWorkspace(String name) {
        Workspace ws = new Workspace(name);
        workspaces.add(ws);
        saveIndex();
        log.info("已创建工作区: {} ({})", name, ws.getId());
        return ws;
    }

    public boolean switchWorkspace(String workspaceId) {
        Workspace target = findById(workspaceId);
        if (target == null) {
            log.warn("工作区不存在: {}", workspaceId);
            return false;
        }
        if (workspaceId.equals(currentWorkspaceId)) {
            log.info("已在当前工作区: {}", workspaceId);
            return true;
        }

        currentWorkspaceId = workspaceId;
        saveCurrentWorkspace();
        updateLogDirProperty();

        log.info("已切换到工作区: {} ({})", target.getName(), workspaceId);
        if (onWorkspaceSwitched != null) {
            onWorkspaceSwitched.accept(target);
        }
        return true;
    }

    public boolean deleteWorkspace(String workspaceId) {
        if (workspaces.size() <= 1) {
            log.warn("不能删除最后一个工作区");
            return false;
        }

        Workspace ws = findById(workspaceId);
        if (ws == null) return false;

        workspaces.remove(ws);
        if (workspaceId.equals(currentWorkspaceId)) {
            currentWorkspaceId = workspaces.getFirst().getId();
        }
        deleteWorkspaceRows(workspaceId);
        saveIndex();

        log.info("已删除工作区: {} ({})", ws.getName(), workspaceId);
        return true;
    }

    public void renameWorkspace(String workspaceId, String newName) {
        Workspace ws = findById(workspaceId);
        if (ws != null) {
            ws.setName(newName);
            saveIndex();
            log.info("已重命名工作区: {} -> {}", workspaceId, newName);
        }
    }

    // ==================== 路径查询 ====================

    public Path getGlobalDataPath() {
        return projectRoot.resolve("data");
    }

    public Path getCurrentBrowserDir() {
        return getGlobalDataPath().resolve("browser").resolve(currentWorkspaceId);
    }

    public Path getCurrentLogDir() {
        return getGlobalDataPath().resolve("logs").resolve(currentWorkspaceId);
    }

    public Workspace getCurrentWorkspace() {
        return findById(currentWorkspaceId);
    }

    public String getCurrentWorkspaceId() {
        return currentWorkspaceId;
    }

    public List<Workspace> getWorkspaces() {
        return Collections.unmodifiableList(workspaces);
    }

    public void setOnWorkspaceSwitched(Consumer<Workspace> callback) {
        this.onWorkspaceSwitched = callback;
    }

    public Workspace findById(String id) {
        return workspaces.stream()
                .filter(ws -> ws.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    // ==================== 内部方法 ====================

    private void updateLogDirProperty() {
        try {
            Files.createDirectories(getCurrentLogDir());
        } catch (IOException e) {
            log.warn("创建日志目录失败: {}", e.getMessage());
        }
        String logDir = getCurrentLogDir().toString();
        System.setProperty("workspace.log.dir", logDir);
        reconfigureLogback();
    }

    private void reconfigureLogback() {
        try {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();

            InputStream configStream = getClass().getResourceAsStream("/logback.xml");
            if (configStream != null) {
                configurator.doConfigure(configStream);
                log.info("Logback 已重新配置，日志目录: {}", System.getProperty("workspace.log.dir"));
            }
        } catch (Exception e) {
            System.err.println("重新配置 Logback 失败: " + e.getMessage());
        }
    }

    // ==================== H2 持久化 ====================

    private void loadIndex() {
        workspaces.clear();
        try (Connection c = AppDatabase.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name, created_at FROM workspaces ORDER BY created_at")) {
            while (rs.next()) {
                Workspace ws = new Workspace();
                ws.setId(rs.getString("id"));
                ws.setName(rs.getString("name"));
                ws.setCreatedAt(rs.getString("created_at"));
                workspaces.add(ws);
            }
        } catch (Exception e) {
            log.error("从 H2 加载工作区索引失败", e);
        }

        currentWorkspaceId = loadCurrentWorkspace();
        if (!workspaces.isEmpty()
                && (currentWorkspaceId == null || findById(currentWorkspaceId) == null)) {
            currentWorkspaceId = workspaces.getFirst().getId();
            saveCurrentWorkspace();
        }
    }

    private void saveIndex() {
        String insert = """
                INSERT INTO workspaces(id, name, created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """;
        try (Connection c = AppDatabase.getConnection();
             Statement del = c.createStatement();
             PreparedStatement ps = c.prepareStatement(insert)) {
            c.setAutoCommit(false);
            del.executeUpdate("DELETE FROM workspaces");
            for (Workspace ws : workspaces) {
                ps.setString(1, ws.getId());
                ps.setString(2, ws.getName());
                ps.setString(3, ws.getCreatedAt());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
            saveCurrentWorkspace();
        } catch (Exception e) {
            log.error("保存工作区索引到 H2 失败", e);
        }
    }

    private String loadCurrentWorkspace() {
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT state_value FROM app_state WHERE state_key = ?")) {
            ps.setString(1, STATE_CURRENT_WORKSPACE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("state_value") : null;
            }
        } catch (Exception e) {
            log.warn("读取当前工作区状态失败: {}", e.getMessage());
            return null;
        }
    }

    private void saveCurrentWorkspace() {
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     MERGE INTO app_state(state_key, state_value, updated_at)
                     KEY(state_key)
                     VALUES (?, ?, CURRENT_TIMESTAMP)
                     """)) {
            ps.setString(1, STATE_CURRENT_WORKSPACE);
            ps.setString(2, currentWorkspaceId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("保存当前工作区状态失败: {}", e.getMessage());
        }
    }

    private void deleteWorkspaceRows(String workspaceId) {
        String[] tables = {
                "app_properties",
                "mcp_servers",
                "site_sessions",
                "site_credentials",
                "scheduled_tasks",
                "custom_agents",
                "plugin_state",
                "plugin_storage",
                "command_whitelist",
                "chat_messages",
                "chat_sessions",
                "token_usage_daily",
                "skill_usage",
                "skill_proposals",
                "sdd_tasks",
                "sdd_spec_docs",
                "sdd_verify_cache",
                "knowledge_doc_prefs",
                "browser_state"
        };
        try (Connection c = AppDatabase.getConnection()) {
            c.setAutoCommit(false);
            for (String table : tables) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table + " WHERE workspace_id = ?")) {
                    ps.setString(1, workspaceId);
                    ps.executeUpdate();
                }
            }
            c.commit();
        } catch (Exception e) {
            log.warn("删除工作区 H2 数据失败: workspaceId={}, error={}", workspaceId, e.getMessage());
        }
    }

}
