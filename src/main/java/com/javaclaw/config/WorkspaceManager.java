package com.javaclaw.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * 工作区管理器
 *
 * <p>管理工作区的创建、切换、删除和持久化。每个工作区拥有独立的目录结构：
 * <pre>
 * workspaces/
 *   ├── workspaces.json                # 工作区索引 + 当前工作区 ID
 *   ├── {workspace-id}/
 *   │   ├── javaclaw-agent.properties  # 智能体配置
 *   │   ├── javaclaw-email.properties  # 邮件配置
 *   │   ├── javaclaw-notification.properties  # 通知配置
 *   │   ├── data/                      # 数据目录
 *   │   │   ├── screenshots/
 *   │   │   ├── chat/sessions/
 *   │   │   └── scheduled-tasks.json
 *   │   ├── browser/                   # 浏览器状态（Playwright pw-cookies.json 等）
 *   │   └── logs/                      # 日志
 *   │       ├── javaclaw.log
 *   │       └── task.log
 * </pre>
 *
 * @author JavaClaw
 */
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private static final String WORKSPACES_DIR = "workspaces";
    private static final String INDEX_FILE = "workspaces.json";
    private static final String DEFAULT_WORKSPACE_NAME = "默认工作区";

    private static WorkspaceManager instance;

    private final Path workspacesRoot;
    private final Path indexFile;
    private final ObjectMapper objectMapper;

    private final List<Workspace> workspaces = new ArrayList<>();
    private String currentWorkspaceId;

    /** 工作区切换回调 */
    private Consumer<Workspace> onWorkspaceSwitched;

    private WorkspaceManager() {
        this.workspacesRoot = Path.of(System.getProperty("user.dir"), WORKSPACES_DIR);
        this.indexFile = workspacesRoot.resolve(INDEX_FILE);
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    public static synchronized WorkspaceManager getInstance() {
        if (instance == null) {
            instance = new WorkspaceManager();
        }
        return instance;
    }

    /**
     * 初始化工作区管理器
     *
     * <p>首次运行时自动迁移已有数据到默认工作区。</p>
     */
    public void init() {
        try {
            Files.createDirectories(workspacesRoot);
        } catch (IOException e) {
            log.error("创建工作区根目录失败", e);
            return;
        }

        if (Files.exists(indexFile)) {
            loadIndex();
        } else {
            // 首次运行：创建默认工作区并迁移旧数据
            Workspace defaultWs = new Workspace(DEFAULT_WORKSPACE_NAME);
            workspaces.add(defaultWs);
            currentWorkspaceId = defaultWs.getId();
            ensureWorkspaceDir(defaultWs);
            migrateOldData(defaultWs);
            saveIndex();
            log.info("已创建默认工作区并迁移旧数据: {}", defaultWs.getId());
        }

        // 设置日志目录系统属性（供 logback 使用）
        updateLogDirProperty();

        log.info("工作区管理器已初始化，当前工作区: {} ({})",
                getCurrentWorkspace().getName(), currentWorkspaceId);
    }

    // ==================== 工作区操作 ====================

    /**
     * 创建新工作区
     *
     * @param name 工作区名称
     * @return 新建的工作区
     */
    public Workspace createWorkspace(String name) {
        Workspace ws = new Workspace(name);
        workspaces.add(ws);
        ensureWorkspaceDir(ws);
        saveIndex();
        log.info("已创建工作区: {} ({})", name, ws.getId());
        return ws;
    }

    /**
     * 切换到指定工作区
     *
     * @param workspaceId 目标工作区 ID
     * @return 是否切换成功
     */
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
        saveIndex();
        updateLogDirProperty();

        log.info("已切换到工作区: {} ({})", target.getName(), workspaceId);

        if (onWorkspaceSwitched != null) {
            onWorkspaceSwitched.accept(target);
        }
        return true;
    }

    /**
     * 删除工作区（不能删除最后一个工作区）
     * 如果删除当前工作区，调用方需先切换到其他工作区
     */
    public boolean deleteWorkspace(String workspaceId) {
        if (workspaces.size() <= 1) {
            log.warn("不能删除最后一个工作区");
            return false;
        }

        Workspace ws = findById(workspaceId);
        if (ws == null) return false;

        workspaces.remove(ws);
        saveIndex();

        // 删除工作区目录
        Path wsDir = getWorkspaceDir(workspaceId);
        try {
            deleteDirectoryRecursive(wsDir);
            log.info("已删除工作区: {} ({})", ws.getName(), workspaceId);
        } catch (IOException e) {
            log.error("删除工作区目录失败: {}", wsDir, e);
        }

        return true;
    }

    /**
     * 重命名工作区
     */
    public void renameWorkspace(String workspaceId, String newName) {
        Workspace ws = findById(workspaceId);
        if (ws != null) {
            ws.setName(newName);
            saveIndex();
            log.info("已重命名工作区: {} -> {}", workspaceId, newName);
        }
    }

    // ==================== 路径查询 ====================

    /**
     * 获取当前工作区的根目录
     */
    public Path getCurrentWorkspacePath() {
        return getWorkspaceDir(currentWorkspaceId);
    }

    /**
     * 获取指定工作区的根目录
     */
    public Path getWorkspaceDir(String workspaceId) {
        return workspacesRoot.resolve(workspaceId);
    }

    /**
     * 获取全局共享数据目录（跨工作区共享）
     */
    public Path getGlobalDataPath() {
        return workspacesRoot.resolve("global").resolve("data");
    }

    /**
     * 获取当前工作区的浏览器数据目录
     */
    public Path getCurrentBrowserDir() {
        return getCurrentWorkspacePath().resolve("browser");
    }

    /**
     * 获取当前工作区的日志目录
     */
    public Path getCurrentLogDir() {
        return getCurrentWorkspacePath().resolve("logs");
    }

    /**
     * 获取当前工作区对象
     */
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

    // ==================== 内部方法 ====================

    public Workspace findById(String id) {
        return workspaces.stream()
                .filter(ws -> ws.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    private void ensureWorkspaceDir(Workspace ws) {
        Path wsDir = getWorkspaceDir(ws.getId());
        try {
            Files.createDirectories(wsDir.resolve("data/screenshots"));
            Files.createDirectories(wsDir.resolve("data/chat/sessions"));
            Files.createDirectories(wsDir.resolve("browser"));
            Files.createDirectories(wsDir.resolve("logs"));
            log.debug("工作区目录已创建: {}", wsDir);
        } catch (IOException e) {
            log.error("创建工作区目录失败: {}", wsDir, e);
        }
    }

    /**
     * 迁移旧版数据（项目根目录下的配置和数据）到默认工作区
     */
    private void migrateOldData(Workspace ws) {
        Path wsDir = getWorkspaceDir(ws.getId());
        Path projectRoot = Path.of(System.getProperty("user.dir"));

        // 迁移配置文件
        migrateFile(projectRoot.resolve("javaclaw-agent.properties"),
                wsDir.resolve("javaclaw-agent.properties"));
        migrateFile(projectRoot.resolve("javaclaw-email.properties"),
                wsDir.resolve("javaclaw-email.properties"));
        migrateFile(projectRoot.resolve("javaclaw-notification.properties"),
                wsDir.resolve("javaclaw-notification.properties"));

        // 迁移数据目录
        Path oldDataDir = projectRoot.resolve("data");
        if (Files.isDirectory(oldDataDir)) {
            Path newDataDir = wsDir.resolve("data");
            try {
                copyDirectoryContents(oldDataDir, newDataDir);
                log.info("已迁移旧数据目录到工作区: {}", newDataDir);
            } catch (IOException e) {
                log.warn("迁移旧数据目录失败: {}", e.getMessage());
            }
        }

        // 迁移日志目录
        Path oldLogDir = projectRoot.resolve("logs");
        if (Files.isDirectory(oldLogDir)) {
            Path newLogDir = wsDir.resolve("logs");
            try {
                copyDirectoryContents(oldLogDir, newLogDir);
                log.info("已迁移旧日志目录到工作区: {}", newLogDir);
            } catch (IOException e) {
                log.warn("迁移旧日志目录失败: {}", e.getMessage());
            }
        }
    }

    private void migrateFile(Path source, Path target) {
        if (Files.exists(source) && !Files.exists(target)) {
            try {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                log.info("已迁移文件: {} -> {}", source.getFileName(), target);
            } catch (IOException e) {
                log.warn("迁移文件失败: {} -> {}: {}", source, target, e.getMessage());
            }
        }
    }

    private void copyDirectoryContents(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                Path dest = target.resolve(source.relativize(src));
                try {
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        if (!Files.exists(dest)) {
                            Files.copy(src, dest);
                        }
                    }
                } catch (IOException e) {
                    log.warn("复制文件失败: {}: {}", src, e.getMessage());
                }
            });
        }
    }

    private void deleteDirectoryRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            log.warn("删除文件失败: {}", path);
                        }
                    });
        }
    }

    /**
     * 更新日志目录系统属性并重新配置 Logback
     *
     * <p>Logback 在启动时解析 ${workspace.log.dir}，之后不会自动刷新。
     * 因此切换工作区时需要先更新系统属性，再重新加载 logback.xml。</p>
     */
    private void updateLogDirProperty() {
        String logDir = getCurrentLogDir().toString();
        System.setProperty("workspace.log.dir", logDir);
        reconfigureLogback();
    }

    /**
     * 重新加载 Logback 配置以应用新的日志目录
     */
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
            // 使用 System.err 因为日志系统可能处于不稳定状态
            System.err.println("重新配置 Logback 失败: " + e.getMessage());
        }
    }

    // ==================== 持久化 ====================

    private void loadIndex() {
        try {
            Map<String, Object> data = objectMapper.readValue(indexFile.toFile(),
                    new TypeReference<>() {});
            currentWorkspaceId = (String) data.get("currentWorkspaceId");

            @SuppressWarnings("unchecked")
            List<Map<String, String>> wsList = (List<Map<String, String>>) data.get("workspaces");
            workspaces.clear();
            if (wsList != null) {
                for (Map<String, String> wsMap : wsList) {
                    Workspace ws = new Workspace();
                    ws.setId(wsMap.get("id"));
                    ws.setName(wsMap.get("name"));
                    ws.setCreatedAt(wsMap.get("createdAt"));
                    workspaces.add(ws);
                }
            }

            // 校验当前工作区：索引损坏/空/ID 不匹配都回退到第一个，否则 currentWorkspaceId 可能是 null 传给调用方触发 NPE
            if (workspaces.isEmpty()) {
                log.warn("工作区索引为空，currentWorkspaceId 将置空等待新建工作区");
                currentWorkspaceId = null;
            } else if (currentWorkspaceId == null || findById(currentWorkspaceId) == null) {
                log.warn("currentWorkspaceId [{}] 无效，回退到首个工作区", currentWorkspaceId);
                currentWorkspaceId = workspaces.getFirst().getId();
                try {
                    saveIndex();
                } catch (Exception saveErr) {
                    log.warn("回退后保存工作区索引失败（内存已更新）: {}", saveErr.getMessage());
                }
            }

            log.info("工作区索引已加载: {} 个工作区", workspaces.size());
        } catch (IOException e) {
            log.error("加载工作区索引失败", e);
        }
    }

    private void saveIndex() {
        try {
            List<Map<String, String>> wsList = new ArrayList<>();
            for (Workspace ws : workspaces) {
                Map<String, String> wsMap = new LinkedHashMap<>();
                wsMap.put("id", ws.getId());
                wsMap.put("name", ws.getName());
                wsMap.put("createdAt", ws.getCreatedAt());
                wsList.add(wsMap);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("currentWorkspaceId", currentWorkspaceId);
            data.put("workspaces", wsList);

            objectMapper.writeValue(indexFile.toFile(), data);
            log.debug("工作区索引已保存");
        } catch (IOException e) {
            log.error("保存工作区索引失败", e);
        }
    }
}
