package com.javaclaw.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javaclaw.config.AppDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 多会话聊天记录持久化管理器。
 *
 * <p>数据存储在全局 H2 数据库的 {@code chat_sessions}/{@code chat_messages}
 * 表中，并按 {@code workspace_id} 隔离。启动时只从 H2 读取。</p>
 */
public class ChatHistoryManager {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryManager.class);

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();

    public ChatHistoryManager() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public List<ChatSession> loadSessionIndex() {
        fileLock.writeLock().lock();
        try {
            List<ChatSession> sessions = new ArrayList<>();
            String sql = """
                    SELECT id, title, created_at
                    FROM chat_sessions
                    WHERE workspace_id = ?
                    ORDER BY created_at DESC
                    """;
            try (Connection c = AppDatabase.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, AppDatabase.currentWorkspaceId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        sessions.add(new ChatSession(
                                rs.getString("id"),
                                rs.getString("title"),
                                LocalDateTime.parse(rs.getString("created_at"), TIMESTAMP_FORMATTER),
                                null));
                    }
                }
            }
            log.info("会话索引已从 H2 加载: {} 个会话", sessions.size());
            return sessions;
        } catch (SQLException e) {
            log.error("加载会话索引失败", e);
            return Collections.emptyList();
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    public void saveSessionIndex(List<ChatSession> sessions) {
        fileLock.writeLock().lock();
        try {
            String upsert = """
                    MERGE INTO chat_sessions(workspace_id, id, title, created_at, updated_at)
                    KEY(workspace_id, id)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """;
            try (Connection c = AppDatabase.getConnection();
                 PreparedStatement ps = c.prepareStatement(upsert)) {
                c.setAutoCommit(false);
                deleteRemovedSessions(c, sessions);
                String workspaceId = AppDatabase.currentWorkspaceId();
                for (ChatSession session : sessions) {
                    ps.setString(1, workspaceId);
                    ps.setString(2, session.getId());
                    ps.setString(3, session.getTitle());
                    ps.setString(4, session.getCreatedAt().format(TIMESTAMP_FORMATTER));
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            }
            log.info("会话索引已保存到 H2: {} 个会话", sessions.size());
        } catch (SQLException e) {
            log.error("保存会话索引失败", e);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    public List<ChatMessage> loadSessionMessages(String sessionId) {
        fileLock.readLock().lock();
        try {
            List<ChatMessage> messages = new ArrayList<>();
            String sql = """
                    SELECT role, content, timestamp, image_paths_json, adopted
                    FROM chat_messages
                    WHERE workspace_id = ? AND session_id = ?
                    ORDER BY position
                    """;
            try (Connection c = AppDatabase.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, AppDatabase.currentWorkspaceId());
                ps.setString(2, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ChatMessage msg = new ChatMessage(
                                ChatMessage.Role.valueOf(rs.getString("role")),
                                rs.getString("content"),
                                LocalDateTime.parse(rs.getString("timestamp"), TIMESTAMP_FORMATTER),
                                readStringList(rs.getString("image_paths_json")));
                        msg.setAdopted(rs.getBoolean("adopted"));
                        messages.add(msg);
                    }
                }
            }
            log.info("会话消息已从 H2 加载: {} [{}] {} 条消息", sessionId, "", messages.size());
            return messages;
        } catch (Exception e) {
            log.error("加载会话消息失败: {}", sessionId, e);
            return Collections.emptyList();
        } finally {
            fileLock.readLock().unlock();
        }
    }

    public void saveSessionMessages(String sessionId, List<ChatMessage> messages) {
        fileLock.writeLock().lock();
        try {
            String insert = """
                    INSERT INTO chat_messages(
                        workspace_id, session_id, position, role, content, timestamp, image_paths_json, adopted
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (Connection c = AppDatabase.getConnection();
                 PreparedStatement del = c.prepareStatement("DELETE FROM chat_messages WHERE workspace_id = ? AND session_id = ?");
                 PreparedStatement ps = c.prepareStatement(insert)) {
                c.setAutoCommit(false);
                ensureSessionRow(c, sessionId);
                String workspaceId = AppDatabase.currentWorkspaceId();
                del.setString(1, workspaceId);
                del.setString(2, sessionId);
                del.executeUpdate();
                int pos = 0;
                for (ChatMessage msg : messages) {
                    ps.setString(1, workspaceId);
                    ps.setString(2, sessionId);
                    ps.setInt(3, pos++);
                    ps.setString(4, msg.getRole().name());
                    ps.setString(5, msg.getContent());
                    ps.setString(6, msg.getTimestamp().format(TIMESTAMP_FORMATTER));
                    ps.setString(7, objectMapper.writeValueAsString(msg.getImagePaths()));
                    ps.setBoolean(8, msg.isAdopted());
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            }
            log.debug("会话消息已保存到 H2: {} ({} 条)", sessionId, messages.size());
        } catch (Exception e) {
            log.error("保存会话消息失败: {}", sessionId, e);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    public boolean hasSessionMessages(String sessionId) {
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM chat_messages WHERE workspace_id = ? AND session_id = ? LIMIT 1")) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            ps.setString(2, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("检查会话消息失败: {}", sessionId, e);
            return false;
        }
    }

    public void deleteSession(String sessionId) {
        fileLock.writeLock().lock();
        try (Connection c = AppDatabase.getConnection();
             PreparedStatement msgPs = c.prepareStatement(
                     "DELETE FROM chat_messages WHERE workspace_id = ? AND session_id = ?");
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM chat_sessions WHERE workspace_id = ? AND id = ?")) {
            String workspaceId = AppDatabase.currentWorkspaceId();
            msgPs.setString(1, workspaceId);
            msgPs.setString(2, sessionId);
            msgPs.executeUpdate();
            ps.setString(1, workspaceId);
            ps.setString(2, sessionId);
            ps.executeUpdate();
            log.info("会话已从 H2 删除: {}", sessionId);
        } catch (SQLException e) {
            log.error("删除会话失败: {}", sessionId, e);
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    private void deleteRemovedSessions(Connection c, List<ChatSession> sessions) throws SQLException {
        List<String> ids = sessions.stream().map(ChatSession::getId).toList();
        List<String> existing = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM chat_sessions WHERE workspace_id = ?")) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) existing.add(rs.getString("id"));
            }
        }
        existing.removeAll(ids);
        if (existing.isEmpty()) return;
        try (PreparedStatement msgPs = c.prepareStatement(
                     "DELETE FROM chat_messages WHERE workspace_id = ? AND session_id = ?");
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM chat_sessions WHERE workspace_id = ? AND id = ?")) {
            String workspaceId = AppDatabase.currentWorkspaceId();
            for (String id : existing) {
                msgPs.setString(1, workspaceId);
                msgPs.setString(2, id);
                msgPs.addBatch();

                ps.setString(1, workspaceId);
                ps.setString(2, id);
                ps.addBatch();
            }
            msgPs.executeBatch();
            ps.executeBatch();
        }
    }

    private void ensureSessionRow(Connection c, String sessionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                MERGE INTO chat_sessions(workspace_id, id, title, created_at, updated_at)
                KEY(workspace_id, id)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
            ps.setString(1, AppDatabase.currentWorkspaceId());
            ps.setString(2, sessionId);
            ps.setString(3, sessionId);
            ps.setString(4, LocalDateTime.now().format(TIMESTAMP_FORMATTER));
            ps.executeUpdate();
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析消息图片路径失败，使用空列表", e);
            return Collections.emptyList();
        }
    }

    private List<String> readObjectStringList(Object imgObj) {
        if (!(imgObj instanceof List<?> imgList)) return Collections.emptyList();
        List<String> imagePaths = new ArrayList<>();
        for (Object item : imgList) {
            if (item instanceof String s) imagePaths.add(s);
        }
        return imagePaths;
    }

    @Deprecated
    public void save(List<ChatMessage> messages) {
        // 保留空实现，防止旧调用方编译报错
    }

    @Deprecated
    public List<ChatMessage> load() {
        return Collections.emptyList();
    }

    @Deprecated
    public void clear() {
        // 保留空实现
    }
}
