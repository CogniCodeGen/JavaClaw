package com.javaclaw.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.javaclaw.platform.data.DataRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private static final List<String> WORKSPACE_TABLES = List.of(
            "workflow_checkpoints", "workflow_runs", "workflow_threads",
            "workflow_definitions", "app_properties", "mcp_servers",
            "site_account_bindings", "site_sessions", "site_credentials",
            "scheduled_tasks", "custom_agents", "plugin_state", "plugin_storage",
            "command_whitelist", "conversation_context_summary", "chat_messages", "chat_sessions",
            "token_usage_projection_receipts", "token_usage_daily",
            "skill_usage", "skill_proposals", "sdd_tasks", "sdd_spec_docs",
            "sdd_verify_cache", "knowledge_doc_prefs", "browser_state",
            "inference_workspace_bindings", "agent_definition_versions",
            "agent_definitions", "run_profile_versions", "run_profiles");

    private final List<Workspace> workspaces = new CopyOnWriteArrayList<>();
    private final Path globalDataPath;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private volatile String currentWorkspaceId;

    /** 工作区切换回调 */
    private Consumer<Workspace> onWorkspaceSwitched;

    public WorkspaceManager(
            DataRoot dataRoot,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        globalDataPath = Objects.requireNonNull(dataRoot, "dataRoot").path();
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
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

        String previousWorkspaceId = currentWorkspaceId;
        currentWorkspaceId = workspaceId;
        if (!saveCurrentWorkspace()) {
            currentWorkspaceId = previousWorkspaceId;
            log.warn("工作区状态持久化失败，已取消切换: {}", workspaceId);
            return false;
        }
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
        if (workspaceId.equals(currentWorkspaceId)) {
            log.warn("不能直接删除当前工作区，请先完成切换: {}", workspaceId);
            return false;
        }

        if (!deleteWorkspaceData(workspaceId)) {
            log.warn("工作区数据未完整清理，取消删除: {} ({})", ws.getName(), workspaceId);
            return false;
        }
        workspaces.remove(ws);

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
        return globalDataPath;
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
        return List.copyOf(workspaces);
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

            try (InputStream configStream = getClass().getResourceAsStream("/logback.xml")) {
                if (configStream == null) {
                    throw new IOException("未找到 /logback.xml");
                }
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
        try {
            workspaces.addAll(jdbc.query(
                    "SELECT id, name, created_at FROM workspaces ORDER BY created_at",
                    (result, row) -> {
                        Workspace workspace = new Workspace();
                        workspace.setId(result.getString("id"));
                        workspace.setName(result.getString("name"));
                        workspace.setCreatedAt(result.getString("created_at"));
                        return workspace;
                    }));
        } catch (DataAccessException failure) {
            log.error("从 H2 加载工作区索引失败", failure);
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
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM workspaces");
                jdbc.batchUpdate(insert, workspaces, Math.max(1, workspaces.size()),
                        (statement, workspace) -> {
                            statement.setString(1, workspace.getId());
                            statement.setString(2, workspace.getName());
                            statement.setString(3, workspace.getCreatedAt());
                        });
                saveCurrentWorkspaceRow();
            });
        } catch (DataAccessException failure) {
            log.error("保存工作区索引到 H2 失败", failure);
        }
    }

    private String loadCurrentWorkspace() {
        try {
            return jdbc.query(
                    "SELECT state_value FROM app_state WHERE state_key = ?",
                    (result, row) -> result.getString("state_value"),
                    STATE_CURRENT_WORKSPACE).stream().findFirst().orElse(null);
        } catch (DataAccessException failure) {
            log.warn("读取当前工作区状态失败: {}", failure.getMessage());
            return null;
        }
    }

    private boolean saveCurrentWorkspace() {
        try {
            saveCurrentWorkspaceRow();
            return true;
        } catch (DataAccessException failure) {
            log.warn("保存当前工作区状态失败: {}", failure.getMessage());
            return false;
        }
    }

    private void saveCurrentWorkspaceRow() {
        jdbc.update("""
                MERGE INTO app_state(state_key, state_value, updated_at)
                KEY(state_key)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                """, STATE_CURRENT_WORKSPACE, currentWorkspaceId);
    }

    /**
     * 在同一数据库事务内删除结构化数据，并尽力清理文件桶。
     * 文件系统不具备事务性；部分清理失败后可安全重试，数据库删除会回滚。
     */
    private boolean deleteWorkspaceData(String workspaceId) {
        try {
            transactions.executeWithoutResult(status -> {
                deleteAgentFrameworkData(workspaceId);
                for (String table : WORKSPACE_TABLES) {
                    jdbc.update("DELETE FROM " + table + " WHERE workspace_id = ?", workspaceId);
                }
                if (jdbc.update("DELETE FROM workspaces WHERE id = ?", workspaceId) != 1) {
                    throw new IllegalStateException("工作区索引行不存在: " + workspaceId);
                }
                try {
                    deleteWorkspaceFiles(workspaceId);
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
            return true;
        } catch (RuntimeException failure) {
            log.warn("删除工作区数据失败: workspaceId={}, error={}",
                    workspaceId, failure.getMessage(), failure);
            return false;
        }
    }

    private void deleteAgentFrameworkData(String workspaceId) {
        Integer activeRuns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_runs
                WHERE workspace_id = ?
                  AND state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                """, Integer.class, workspaceId);
        if (activeRuns != null && activeRuns > 0) {
            throw new IllegalStateException(
                    "工作区仍有未终止 Agent Run，不能删除: " + activeRuns);
        }

        List<String> executionPlanIds = jdbc.queryForList("""
                SELECT DISTINCT execution_plan_id FROM agent_runs
                WHERE workspace_id = ?
                """, String.class, workspaceId);
        for (String table : List.of(
                "agent_extension_state", "agent_run_outbox", "agent_run_events")) {
            jdbc.update("DELETE FROM " + table + " WHERE run_id IN ("
                    + "SELECT run_id FROM agent_runs WHERE workspace_id = ?)", workspaceId);
        }
        jdbc.update("DELETE FROM agent_runs WHERE workspace_id = ?", workspaceId);
        for (String planId : executionPlanIds) {
            jdbc.update("""
                    DELETE FROM compiled_execution_plans
                    WHERE plan_id = ?
                      AND NOT EXISTS (
                          SELECT 1 FROM agent_runs WHERE execution_plan_id = ?)
                    """, planId, planId);
        }
    }

    private void deleteWorkspaceFiles(String workspaceId) throws IOException {
        Path dataRoot = globalDataPath;
        List<Path> buckets = List.of(
                dataRoot.resolve("memory-stores"),
                dataRoot.resolve("knowledge").resolve("workspaces"),
                dataRoot.resolve("screenshots"),
                dataRoot.resolve("workspace-data"),
                dataRoot.resolve("browser"),
                dataRoot.resolve("logs")
        );
        for (Path bucket : buckets) {
            Path normalizedBucket = bucket.toAbsolutePath().normalize();
            Path target = normalizedBucket.resolve(workspaceId).normalize();
            if (!normalizedBucket.equals(target.getParent())) {
                throw new IOException("非法工作区文件路径: " + workspaceId);
            }
            deleteDirectoryTree(target);
        }
    }

    private static void deleteDirectoryTree(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error)
                    throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

}
