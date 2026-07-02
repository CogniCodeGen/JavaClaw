package com.javaclaw.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.config.DataManager;
import com.javaclaw.util.AtomicFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 多会话聊天记录持久化管理器
 *
 * <p>支持多会话管理，数据存储结构：
 * <ul>
 *   <li>{@code data/chat/sessions_index.json} — 会话索引（ID、标题、创建时间）</li>
 *   <li>{@code data/chat/sessions/{id}.json} — 每个会话的消息记录</li>
 * </ul>
 * </p>
 *
 * @author JavaClaw
 */
public class ChatHistoryManager {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryManager.class);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final DataManager dataManager;
    /** 保护会话索引的读写；按会话单独的消息文件用同一锁也够用（会话文件读多写少） */
    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();

    public ChatHistoryManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.dataManager = DataManager.getInstance();
    }

    // ==================== 会话索引管理 ====================

    /**
     * 加载所有会话的元信息（不含消息内容）
     *
     * @return 会话列表（按创建时间排序，最新在前）
     */
    public List<ChatSession> loadSessionIndex() {
        Path indexFile = dataManager.getSessionsIndexFile();
        if (!Files.exists(indexFile)) {
            return migrateOldHistory();
        }
        fileLock.readLock().lock();
        try {
            List<Map<String, String>> records = objectMapper.readValue(
                    indexFile.toFile(),
                    new TypeReference<>() {}
            );

            List<ChatSession> sessions = new ArrayList<>();
            for (Map<String, String> record : records) {
                String id = record.get("id");
                String title = record.get("title");
                LocalDateTime createdAt = LocalDateTime.parse(
                        record.get("createdAt"), TIMESTAMP_FORMATTER);
                sessions.add(new ChatSession(id, title, createdAt, null));
            }
            log.info("会话索引已加载: {} 个会话", sessions.size());
            return sessions;
        } catch (Exception e) {
            log.error("加载会话索引失败", e);
            return Collections.emptyList();
        } finally {
            fileLock.readLock().unlock();
        }
    }

    /**
     * 保存会话索引
     */
    public void saveSessionIndex(List<ChatSession> sessions) {
        fileLock.writeLock().lock();
        try {
            List<Map<String, String>> records = new ArrayList<>();
            for (ChatSession session : sessions) {
                Map<String, String> record = new LinkedHashMap<>();
                record.put("id", session.getId());
                record.put("title", session.getTitle());
                record.put("createdAt", session.getCreatedAt().format(TIMESTAMP_FORMATTER));
                records.add(record);
            }
            AtomicFileWriter.writeJson(objectMapper, dataManager.getSessionsIndexFile().toFile(), records);
            log.info("会话索引已保存: {} 个会话", sessions.size());
        } catch (IOException e) {
            log.error("保存会话索引失败", e);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    // ==================== 单会话消息管理 ====================

    /**
     * 加载指定会话的消息列表
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    public List<ChatMessage> loadSessionMessages(String sessionId) {
        Path sessionFile = dataManager.getSessionFile(sessionId);
        if (!Files.exists(sessionFile)) {
            log.info("会话消息文件不存在: {}", sessionId);
            return Collections.emptyList();
        }

        fileLock.readLock().lock();
        try {
            List<Map<String, Object>> records = objectMapper.readValue(
                    sessionFile.toFile(),
                    new TypeReference<>() {}
            );

            List<ChatMessage> messages = new ArrayList<>();
            for (Map<String, Object> record : records) {
                ChatMessage.Role role = ChatMessage.Role.valueOf((String) record.get("role"));
                String content = (String) record.get("content");
                LocalDateTime timestamp = LocalDateTime.parse(
                        (String) record.get("timestamp"), TIMESTAMP_FORMATTER);

                List<String> imagePaths = Collections.emptyList();
                Object imgObj = record.get("imagePaths");
                if (imgObj instanceof List<?> imgList) {
                    imagePaths = new ArrayList<>();
                    for (Object item : imgList) {
                        if (item instanceof String s) {
                            imagePaths.add(s);
                        }
                    }
                }
                ChatMessage msg = new ChatMessage(role, content, timestamp, imagePaths);
                Object adoptedObj = record.get("adopted");
                if (adoptedObj instanceof Boolean b) {
                    msg.setAdopted(b);
                }
                messages.add(msg);
            }

            log.info("会话消息已加载: {} [{}] {} 条消息", sessionId, "", messages.size());
            return messages;
        } catch (Exception e) {
            log.error("加载会话消息失败: {}", sessionId, e);
            return Collections.emptyList();
        } finally {
            fileLock.readLock().unlock();
        }
    }

    /**
     * 保存指定会话的消息列表
     *
     * @param sessionId 会话 ID
     * @param messages  消息列表
     */
    public void saveSessionMessages(String sessionId, List<ChatMessage> messages) {
        fileLock.writeLock().lock();
        try {
            List<Map<String, Object>> records = new ArrayList<>();
            for (ChatMessage msg : messages) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("role", msg.getRole().name());
                record.put("content", msg.getContent());
                record.put("timestamp", msg.getTimestamp().format(TIMESTAMP_FORMATTER));
                if (!msg.getImagePaths().isEmpty()) {
                    record.put("imagePaths", msg.getImagePaths());
                }
                if (msg.isAdopted()) {
                    record.put("adopted", true);
                }
                records.add(record);
            }
            AtomicFileWriter.writeJson(objectMapper, dataManager.getSessionFile(sessionId).toFile(), records);
            log.debug("会话消息已保存: {} ({} 条)", sessionId, messages.size());
        } catch (IOException e) {
            log.error("保存会话消息失败: {}", sessionId, e);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    /**
     * 检查指定会话是否有已持久化的消息（消息文件存在）
     */
    public boolean hasSessionMessages(String sessionId) {
        return Files.exists(dataManager.getSessionFile(sessionId));
    }

    /**
     * 删除指定会话的消息文件
     */
    public void deleteSession(String sessionId) {
        fileLock.writeLock().lock();
        try {
            Files.deleteIfExists(dataManager.getSessionFile(sessionId));
            log.info("会话消息文件已删除: {}", sessionId);
        } catch (IOException e) {
            log.error("删除会话消息文件失败: {}", sessionId, e);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    // ==================== 旧版数据迁移 ====================

    /**
     * 将旧版 chat_history.json 迁移为多会话格式
     *
     * @return 迁移后的会话列表（0 或 1 个）
     */
    private List<ChatSession> migrateOldHistory() {
        Path oldFile = dataManager.getChatHistoryFile();
        if (!Files.exists(oldFile)) {
            log.info("无旧版聊天记录，跳过迁移");
            return Collections.emptyList();
        }

        try {
            List<Map<String, String>> records = objectMapper.readValue(
                    oldFile.toFile(),
                    new TypeReference<>() {}
            );

            if (records.isEmpty()) {
                return Collections.emptyList();
            }

            // 从旧记录中恢复消息
            List<ChatMessage> messages = new ArrayList<>();
            for (Map<String, String> record : records) {
                ChatMessage.Role role = ChatMessage.Role.valueOf(record.get("role"));
                String content = record.get("content");
                LocalDateTime timestamp = LocalDateTime.parse(
                        record.get("timestamp"), TIMESTAMP_FORMATTER);
                messages.add(new ChatMessage(role, content, timestamp));
            }

            // 创建一个迁移会话
            ChatSession session = new ChatSession("迁移的会话");
            session.getMessages().addAll(messages);
            session.autoTitle();

            // 保存为新格式
            List<ChatSession> sessions = new ArrayList<>();
            sessions.add(session);
            saveSessionMessages(session.getId(), messages);
            saveSessionIndex(sessions);

            log.info("旧版聊天记录已迁移为会话: {} ({} 条消息)", session.getId(), messages.size());
            return sessions;
        } catch (Exception e) {
            log.error("迁移旧版聊天记录失败", e);
            return Collections.emptyList();
        }
    }

    // ==================== 兼容旧接口（废弃） ====================

    /**
     * @deprecated 使用 {@link #saveSessionMessages(String, List)} 替代
     */
    @Deprecated
    public void save(List<ChatMessage> messages) {
        // 保留空实现，防止旧调用方编译报错
    }

    /**
     * @deprecated 使用 {@link #loadSessionMessages(String)} 替代
     */
    @Deprecated
    public List<ChatMessage> load() {
        return Collections.emptyList();
    }

    /**
     * @deprecated 使用 {@link #deleteSession(String)} 替代
     */
    @Deprecated
    public void clear() {
        // 保留空实现
    }
}
