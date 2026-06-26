package com.javaclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据目录管理器（工作区感知）
 *
 * <p>统一管理应用运行数据的存储路径。所有数据保存在当前工作区目录下的 {@code data/} 文件夹中：
 * <ul>
 *   <li>{@code data/screenshots/} — 截图文件</li>
 *   <li>{@code data/chat/} — 聊天记录</li>
 * </ul>
 * </p>
 *
 * @author JavaClaw
 */
public class DataManager {

    private static final Logger log = LoggerFactory.getLogger(DataManager.class);

    /** 数据根目录名称 */
    private static final String DATA_DIR = "data";

    /** 截图子目录 */
    private static final String SCREENSHOTS_DIR = "screenshots";

    /** 聊天记录子目录 */
    private static final String CHAT_DIR = "chat";

    /** 会话存储子目录 */
    private static final String SESSIONS_DIR = "sessions";

    /** 知识库数据子目录 */
    private static final String KNOWLEDGE_DIR = "knowledge";

    /** 任务事件子目录（JSONL 按任务 ID 分文件） */
    private static final String TASK_EVENTS_DIR = "task-events";

    /** 会话索引文件名 */
    private static final String SESSIONS_INDEX_FILE = "sessions_index.json";

    /** 聊天记录文件名（旧版兼容） */
    private static final String CHAT_HISTORY_FILE = "chat_history.json";

    private static DataManager INSTANCE;

    private Path dataRoot;
    private Path screenshotsDir;
    private Path chatDir;
    private Path knowledgeDir;
    private Path globalKnowledgeDir;
    private Path sessionsDir;
    private Path sessionsIndexFile;
    private Path chatHistoryFile;
    private Path taskEventsDir;

    private DataManager() {
        resolvePaths();
        initDirectories();
    }

    public static synchronized DataManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new DataManager();
        }
        return INSTANCE;
    }

    /**
     * 重新加载路径（工作区切换时调用）
     */
    public void reload() {
        resolvePaths();
        initDirectories();
        log.info("数据目录已重新加载: {}", dataRoot.toAbsolutePath());
    }

    /**
     * 根据当前工作区计算所有路径
     */
    private void resolvePaths() {
        Path workspacePath = WorkspaceManager.getInstance().getCurrentWorkspacePath();
        dataRoot = workspacePath.resolve(DATA_DIR);
        screenshotsDir = dataRoot.resolve(SCREENSHOTS_DIR);
        chatDir = dataRoot.resolve(CHAT_DIR);
        knowledgeDir = dataRoot.resolve(KNOWLEDGE_DIR);
        globalKnowledgeDir = WorkspaceManager.getInstance().getGlobalDataPath().resolve(KNOWLEDGE_DIR);
        sessionsDir = chatDir.resolve(SESSIONS_DIR);
        sessionsIndexFile = chatDir.resolve(SESSIONS_INDEX_FILE);
        chatHistoryFile = chatDir.resolve(CHAT_HISTORY_FILE);
        taskEventsDir = dataRoot.resolve(TASK_EVENTS_DIR);
    }

    /**
     * 初始化数据目录结构
     */
    private void initDirectories() {
        try {
            Files.createDirectories(screenshotsDir);
            Files.createDirectories(chatDir);
            Files.createDirectories(knowledgeDir);
            Files.createDirectories(globalKnowledgeDir);
            Files.createDirectories(sessionsDir);
            Files.createDirectories(taskEventsDir);
            log.info("数据目录已初始化: {}", dataRoot.toAbsolutePath());
        } catch (IOException e) {
            log.error("创建数据目录失败", e);
        }
    }

    /**
     * 获取截图保存目录
     */
    public Path getScreenshotsDir() {
        return screenshotsDir;
    }

    /**
     * 获取当前工作区的知识库数据目录
     */
    public Path getKnowledgeDir() {
        return knowledgeDir;
    }

    /**
     * 获取全局知识库数据目录（跨工作区共享）
     */
    public Path getGlobalKnowledgeDir() {
        return globalKnowledgeDir;
    }

    /**
     * 获取聊天记录目录
     */
    public Path getChatDir() {
        return chatDir;
    }

    /**
     * 获取聊天记录文件路径
     */
    public Path getChatHistoryFile() {
        return chatHistoryFile;
    }

    /**
     * 获取会话存储目录
     */
    public Path getSessionsDir() {
        return sessionsDir;
    }

    /**
     * 获取会话索引文件路径
     */
    public Path getSessionsIndexFile() {
        return sessionsIndexFile;
    }

    /**
     * 获取指定会话的消息文件路径
     *
     * @param sessionId 会话 ID（仅允许字母、数字、下划线、连字符）
     * @return 消息文件路径（如 data/chat/sessions/abc12345.json）
     * @throws IllegalArgumentException 如果 sessionId 含非法字符（防止路径穿越）
     */
    public Path getSessionFile(String sessionId) {
        validatePathSegment(sessionId, "sessionId");
        return sessionsDir.resolve(sessionId + ".json");
    }

    /**
     * 校验路径片段，防止路径穿越攻击
     *
     * @param segment 路径片段（如会话 ID、文件名）
     * @param name    参数名（用于错误提示）
     */
    private void validatePathSegment(String segment, String name) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (!segment.matches("[a-zA-Z0-9_\\-]+")) {
            throw new IllegalArgumentException(name + " 包含非法字符: " + segment);
        }
    }

    /**
     * 获取数据根目录
     */
    public Path getDataRoot() {
        return dataRoot;
    }

    /**
     * 获取任务事件 JSONL 存储目录
     */
    public Path getTaskEventsDir() {
        return taskEventsDir;
    }

    /**
     * 获取指定任务的事件文件路径
     */
    public Path getTaskEventsFile(String taskId) {
        validatePathSegment(taskId, "taskId");
        return taskEventsDir.resolve(taskId + ".jsonl");
    }
}
