package com.javaclaw.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 工作区隔离的聊天历史仓储。
 *
 * <p>实例由根 Spring Context 管理且线程安全。每个公开操作只捕获一次工作区 ID；会话索引
 * 替换、消息替换和级联删除分别在单一事务中完成，因此工作区切换不会把一次写入拆到两个
 * 工作区。预期的数据库失败会记录日志并返回空结果，调用方的 FX 状态不会被半更新。</p>
 */
public final class ChatHistoryManager {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryManager.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Supplier<String> workspaceId;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ChatHistoryManager(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json,
            Supplier<String> workspaceId) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(json, "json");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    public List<ChatSession> loadSessionIndex() {
        String workspace = currentWorkspaceId();
        lock.readLock().lock();
        try {
            List<ChatSession> sessions = jdbc.query("""
                    SELECT id, title, created_at
                    FROM chat_sessions
                    WHERE workspace_id = ?
                    ORDER BY created_at DESC
                    """, (row, index) -> new ChatSession(
                            row.getString("id"),
                            row.getString("title"),
                            LocalDateTime.parse(
                                    row.getString("created_at"), TIMESTAMP_FORMATTER),
                            null), workspace);
            log.info("会话索引已从 H2 加载: {} 个会话", sessions.size());
            return sessions;
        } catch (RuntimeException failure) {
            log.error("加载会话索引失败: workspace={}", workspace, failure);
            return List.of();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void saveSessionIndex(List<ChatSession> sessions) {
        List<ChatSession> snapshot = List.copyOf(sessions);
        String workspace = currentWorkspaceId();
        lock.writeLock().lock();
        try {
            transactions.executeWithoutResult(status -> {
                deleteRemovedSessions(workspace, snapshot);
                if (snapshot.isEmpty()) return;
                jdbc.batchUpdate("""
                        MERGE INTO chat_sessions(
                            workspace_id, id, title, created_at, updated_at)
                        KEY(workspace_id, id)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, snapshot, snapshot.size(), (statement, session) -> {
                            statement.setString(1, workspace);
                            statement.setString(2, session.getId());
                            statement.setString(3, session.getTitle());
                            statement.setString(4,
                                    session.getCreatedAt().format(TIMESTAMP_FORMATTER));
                        });
            });
            log.info("会话索引已保存到 H2: {} 个会话", snapshot.size());
        } catch (DataAccessException failure) {
            log.error("保存会话索引失败: workspace={}", workspace, failure);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ChatMessage> loadSessionMessages(String sessionId) {
        String checkedSessionId = requireSessionId(sessionId);
        String workspace = currentWorkspaceId();
        lock.readLock().lock();
        try {
            List<ChatMessage> messages = jdbc.query("""
                    SELECT role, content, timestamp, image_paths_json, adopted,
                           delivery_state, input_tokens, output_tokens, duration_ms
                    FROM chat_messages
                    WHERE workspace_id = ? AND session_id = ?
                    ORDER BY position
                    """, (row, index) -> readMessage(row), workspace, checkedSessionId);
            log.info("会话消息已从 H2 加载: {} {} 条消息", checkedSessionId, messages.size());
            return messages;
        } catch (RuntimeException failure) {
            log.error("加载会话消息失败: workspace={}, session={}",
                    workspace, checkedSessionId, failure);
            return List.of();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void saveSessionMessages(String sessionId, List<ChatMessage> messages) {
        String checkedSessionId = requireSessionId(sessionId);
        List<PersistedMessage> snapshot;
        try {
            snapshot = snapshotMessages(messages);
        } catch (RuntimeException failure) {
            log.error("序列化会话消息失败: session={}", checkedSessionId, failure);
            return;
        }
        String workspace = currentWorkspaceId();
        lock.writeLock().lock();
        try {
            transactions.executeWithoutResult(status -> {
                ensureSessionRow(workspace, checkedSessionId);
                jdbc.update("""
                        DELETE FROM chat_messages
                        WHERE workspace_id = ? AND session_id = ?
                        """, workspace, checkedSessionId);
                if (!snapshot.isEmpty()) insertMessages(workspace, checkedSessionId, snapshot);
            });
            log.debug("会话消息已保存到 H2: {} ({} 条)",
                    checkedSessionId, snapshot.size());
        } catch (DataAccessException failure) {
            log.error("保存会话消息失败: workspace={}, session={}",
                    workspace, checkedSessionId, failure);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean hasSessionMessages(String sessionId) {
        String checkedSessionId = requireSessionId(sessionId);
        String workspace = currentWorkspaceId();
        try {
            Long count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM chat_messages
                    WHERE workspace_id = ? AND session_id = ?
                    """, Long.class, workspace, checkedSessionId);
            return count != null && count > 0;
        } catch (DataAccessException failure) {
            log.warn("检查会话消息失败: workspace={}, session={}",
                    workspace, checkedSessionId, failure);
            return false;
        }
    }

    public void deleteSession(String sessionId) {
        String checkedSessionId = requireSessionId(sessionId);
        String workspace = currentWorkspaceId();
        lock.writeLock().lock();
        try {
            transactions.executeWithoutResult(status -> deleteSessions(
                    workspace, List.of(checkedSessionId)));
            log.info("会话已从 H2 删除: {}", checkedSessionId);
        } catch (DataAccessException failure) {
            log.error("删除会话失败: workspace={}, session={}",
                    workspace, checkedSessionId, failure);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ChatMessage readMessage(java.sql.ResultSet row) throws java.sql.SQLException {
        ChatMessage message = new ChatMessage(
                ChatMessage.Role.valueOf(row.getString("role")),
                row.getString("content"),
                LocalDateTime.parse(row.getString("timestamp"), TIMESTAMP_FORMATTER),
                readStringList(row.getString("image_paths_json")));
        message.setAdopted(row.getBoolean("adopted"));
        String state = row.getString("delivery_state");
        message.setDeliveryState(state == null || state.isBlank()
                ? null : DeliveryState.valueOf(state));
        Long input = nullableLong(row, "input_tokens");
        Long output = nullableLong(row, "output_tokens");
        Long duration = nullableLong(row, "duration_ms");
        message.setMetrics(input == null && output == null && duration == null
                ? null : new TurnMetrics(
                        input == null ? 0 : input,
                        output == null ? 0 : output,
                        duration == null ? 0 : duration));
        return message;
    }

    private List<PersistedMessage> snapshotMessages(List<ChatMessage> messages) {
        List<ChatMessage> source = List.copyOf(messages);
        List<PersistedMessage> snapshot = new ArrayList<>(source.size());
        for (int position = 0; position < source.size(); position++) {
            ChatMessage message = source.get(position);
            try {
                snapshot.add(new PersistedMessage(
                        position, message, json.writeValueAsString(message.getImagePaths())));
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("无法序列化消息图片路径", failure);
            }
        }
        return List.copyOf(snapshot);
    }

    private void insertMessages(
            String workspace,
            String sessionId,
            List<PersistedMessage> messages) {
        jdbc.batchUpdate("""
                INSERT INTO chat_messages(
                    workspace_id, session_id, position, role, content, timestamp,
                    image_paths_json, adopted, delivery_state,
                    input_tokens, output_tokens, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, messages, messages.size(), (statement, persisted) -> {
                    ChatMessage message = persisted.message();
                    statement.setString(1, workspace);
                    statement.setString(2, sessionId);
                    statement.setInt(3, persisted.position());
                    statement.setString(4, message.getRole().name());
                    statement.setString(5, message.getContent());
                    statement.setString(6,
                            message.getTimestamp().format(TIMESTAMP_FORMATTER));
                    statement.setString(7, persisted.imagePathsJson());
                    statement.setBoolean(8, message.isAdopted());
                    setNullableString(statement, 9, message.getDeliveryState() == null
                            ? null : message.getDeliveryState().name());
                    TurnMetrics metrics = message.getMetrics();
                    setNullableLong(statement, 10,
                            metrics == null ? null : metrics.inputTokens());
                    setNullableLong(statement, 11,
                            metrics == null ? null : metrics.outputTokens());
                    setNullableLong(statement, 12,
                            metrics == null ? null : metrics.durationMs());
                });
    }

    private void deleteRemovedSessions(String workspace, List<ChatSession> sessions) {
        List<String> retained = sessions.stream().map(ChatSession::getId).toList();
        List<String> removed = jdbc.queryForList(
                "SELECT id FROM chat_sessions WHERE workspace_id = ?",
                String.class, workspace).stream()
                .filter(id -> !retained.contains(id))
                .toList();
        deleteSessions(workspace, removed);
    }

    private void deleteSessions(String workspace, List<String> sessionIds) {
        if (sessionIds.isEmpty()) return;
        jdbc.batchUpdate("""
                DELETE FROM chat_messages
                WHERE workspace_id = ? AND session_id = ?
                """, sessionIds, sessionIds.size(), (statement, id) -> {
                    statement.setString(1, workspace);
                    statement.setString(2, id);
                });
        jdbc.batchUpdate("""
                DELETE FROM chat_sessions
                WHERE workspace_id = ? AND id = ?
                """, sessionIds, sessionIds.size(), (statement, id) -> {
                    statement.setString(1, workspace);
                    statement.setString(2, id);
                });
    }

    private void ensureSessionRow(String workspace, String sessionId) {
        jdbc.update("""
                MERGE INTO chat_sessions(workspace_id, id, title, created_at, updated_at)
                KEY(workspace_id, id)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, workspace, sessionId, sessionId,
                LocalDateTime.now().format(TIMESTAMP_FORMATTER));
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return json.readValue(value, new TypeReference<List<String>>() { });
        } catch (Exception failure) {
            log.warn("解析消息图片路径失败，使用空列表", failure);
            return Collections.emptyList();
        }
    }

    private String currentWorkspaceId() {
        String value = workspaceId.get();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("当前工作区 ID 尚未初始化");
        }
        return value;
    }

    private static String requireSessionId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        return value;
    }

    private static Long nullableLong(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static void setNullableString(
            java.sql.PreparedStatement statement, int index, String value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setNullableLong(
            java.sql.PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }

    private record PersistedMessage(
            int position,
            ChatMessage message,
            String imagePathsJson) { }
}
