package com.javaclaw.infrastructure.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaclaw.application.chat.ChatHistoryApplicationService.DeliveryStatus;
import com.javaclaw.application.chat.ChatHistoryApplicationService.MessageRole;
import com.javaclaw.application.chat.ChatHistoryApplicationService.MessageSnapshot;
import com.javaclaw.application.chat.ChatHistoryApplicationService.SessionSnapshot;
import com.javaclaw.application.chat.ChatHistoryApplicationService.TurnUsage;
import com.javaclaw.application.chat.ChatHistoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Transactional H2 implementation of workspace-scoped chat history storage.
 *
 * <p>The store is thread-safe. Every operation receives an immutable workspace id and snapshot
 * writes run in one transaction, so a delayed save cannot follow a concurrent workspace
 * transition.</p>
 */
public final class JdbcChatHistoryStore implements ChatHistoryPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcChatHistoryStore.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public JdbcChatHistoryStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions =
                new TransactionTemplate(
                        Objects.requireNonNull(transactionManager, "transactionManager"));
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public List<SessionSnapshot> sessions(String workspaceId) {
        String workspace = requireWorkspaceId(workspaceId);
        lock.readLock().lock();
        try {
            List<SessionSnapshot> sessions =
                    jdbc.query(
                            """
                            SELECT id, title, created_at
                            FROM chat_sessions
                            WHERE workspace_id = ?
                            ORDER BY created_at DESC
                            """,
                            (row, index) ->
                                    new SessionSnapshot(
                                            row.getString("id"),
                                            row.getString("title"),
                                            LocalDateTime.parse(
                                                    row.getString("created_at"),
                                                    TIMESTAMP_FORMATTER)),
                            workspace);
            log.info("会话索引已从 H2 加载: {} 个会话", sessions.size());
            return sessions;
        } catch (RuntimeException failure) {
            log.error("加载会话索引失败: workspace={}", workspace, failure);
            return List.of();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void saveSessions(String workspaceId, List<SessionSnapshot> sessions) {
        List<SessionSnapshot> snapshot = List.copyOf(sessions);
        String workspace = requireWorkspaceId(workspaceId);
        lock.writeLock().lock();
        try {
            transactions.executeWithoutResult(
                    status -> {
                        deleteRemovedSessions(workspace, snapshot);
                        if (snapshot.isEmpty()) return;
                        jdbc.batchUpdate(
                                """
                                MERGE INTO chat_sessions(
                                    workspace_id, id, title, created_at, updated_at)
                                KEY(workspace_id, id)
                                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                                """,
                                snapshot,
                                snapshot.size(),
                                (statement, session) -> {
                                    statement.setString(1, workspace);
                                    statement.setString(2, session.id());
                                    statement.setString(3, session.title());
                                    statement.setString(
                                            4, session.createdAt().format(TIMESTAMP_FORMATTER));
                                });
                    });
            log.info("会话索引已保存到 H2: {} 个会话", snapshot.size());
        } catch (DataAccessException failure) {
            log.error("保存会话索引失败: workspace={}", workspace, failure);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<MessageSnapshot> messages(String workspaceId, String sessionId) {
        String checkedSessionId = requireSessionId(sessionId);
        String workspace = requireWorkspaceId(workspaceId);
        lock.readLock().lock();
        try {
            List<MessageSnapshot> messages =
                    jdbc.query(
                            """
                            SELECT position, message_id, role, content, timestamp,
                                   image_paths_json, adopted,
                                   delivery_state, input_tokens, cache_read_input_tokens,
                                   cache_write_input_tokens, output_tokens, reasoning_tokens,
                                   model_calls, duration_ms
                            FROM chat_messages
                            WHERE workspace_id = ? AND session_id = ?
                            ORDER BY position
                            """,
                            (row, index) -> readMessage(row, workspace, checkedSessionId),
                            workspace,
                            checkedSessionId);
            log.info("会话消息已从 H2 加载: {} {} 条消息", checkedSessionId, messages.size());
            return messages;
        } catch (RuntimeException failure) {
            log.error(
                    "加载会话消息失败: workspace={}, session={}",
                    workspace,
                    checkedSessionId,
                    failure);
            return List.of();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void saveMessages(
            String workspaceId, String sessionId, List<MessageSnapshot> messages) {
        String checkedSessionId = requireSessionId(sessionId);
        List<PersistedMessage> snapshot;
        try {
            snapshot = snapshotMessages(messages);
        } catch (RuntimeException failure) {
            log.error("序列化会话消息失败: session={}", checkedSessionId, failure);
            return;
        }
        String workspace = requireWorkspaceId(workspaceId);
        lock.writeLock().lock();
        try {
            transactions.executeWithoutResult(
                    status -> {
                        ensureSessionRow(workspace, checkedSessionId);
                        jdbc.update(
                                """
                                DELETE FROM chat_messages
                                WHERE workspace_id = ? AND session_id = ?
                                """,
                                workspace,
                                checkedSessionId);
                        if (!snapshot.isEmpty()) {
                            insertMessages(workspace, checkedSessionId, snapshot);
                        }
                    });
            log.debug("会话消息已保存到 H2: {} ({} 条)", checkedSessionId, snapshot.size());
        } catch (DataAccessException failure) {
            log.error(
                    "保存会话消息失败: workspace={}, session={}",
                    workspace,
                    checkedSessionId,
                    failure);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean hasMessages(String workspaceId, String sessionId) {
        String checkedSessionId = requireSessionId(sessionId);
        String workspace = requireWorkspaceId(workspaceId);
        try {
            Long count =
                    jdbc.queryForObject(
                            """
                            SELECT COUNT(*) FROM chat_messages
                            WHERE workspace_id = ? AND session_id = ?
                            """,
                            Long.class,
                            workspace,
                            checkedSessionId);
            return count != null && count > 0;
        } catch (DataAccessException failure) {
            log.warn(
                    "检查会话消息失败: workspace={}, session={}",
                    workspace,
                    checkedSessionId,
                    failure);
            return false;
        }
    }

    @Override
    public void delete(String workspaceId, String sessionId) {
        String checkedSessionId = requireSessionId(sessionId);
        String workspace = requireWorkspaceId(workspaceId);
        lock.writeLock().lock();
        try {
            transactions.executeWithoutResult(
                    status -> deleteSessions(workspace, List.of(checkedSessionId)));
            log.info("会话已从 H2 删除: {}", checkedSessionId);
        } catch (DataAccessException failure) {
            log.error(
                    "删除会话失败: workspace={}, session={}",
                    workspace,
                    checkedSessionId,
                    failure);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private MessageSnapshot readMessage(
            ResultSet row, String workspace, String sessionId) throws SQLException {
        String state = row.getString("delivery_state");
        Long input = nullableLong(row, "input_tokens");
        Long cacheRead = nullableLong(row, "cache_read_input_tokens");
        Long cacheWrite = nullableLong(row, "cache_write_input_tokens");
        Long output = nullableLong(row, "output_tokens");
        Long reasoning = nullableLong(row, "reasoning_tokens");
        Long calls = nullableLong(row, "model_calls");
        Long duration = nullableLong(row, "duration_ms");
        TurnUsage usage =
                input == null && cacheRead == null && cacheWrite == null && output == null
                        && reasoning == null && calls == null && duration == null
                        ? null
                        : new TurnUsage(
                                input == null ? 0 : input,
                                cacheRead == null ? 0 : cacheRead,
                                cacheWrite == null ? 0 : cacheWrite,
                                output == null ? 0 : output,
                                reasoning == null ? 0 : reasoning,
                                calls == null ? 0 : calls,
                                duration == null ? 0 : duration);
        String messageId = row.getString("message_id");
        if (messageId == null || messageId.isBlank()) {
            messageId = LegacyChatMessageIds.derive(
                    workspace,
                    sessionId,
                    row.getInt("position"),
                    row.getString("role"),
                    row.getString("content"),
                    row.getString("timestamp"));
        }
        return new MessageSnapshot(
                MessageRole.valueOf(row.getString("role")),
                row.getString("content"),
                LocalDateTime.parse(row.getString("timestamp"), TIMESTAMP_FORMATTER),
                readStringList(row.getString("image_paths_json")),
                row.getBoolean("adopted"),
                state == null || state.isBlank() ? null : DeliveryStatus.valueOf(state),
                usage,
                messageId);
    }

    private List<PersistedMessage> snapshotMessages(List<MessageSnapshot> messages) {
        List<MessageSnapshot> source = List.copyOf(messages);
        List<PersistedMessage> snapshot = new ArrayList<>(source.size());
        for (int position = 0; position < source.size(); position++) {
            MessageSnapshot message = source.get(position);
            try {
                snapshot.add(
                        new PersistedMessage(
                                position,
                                message,
                                json.writeValueAsString(message.imagePaths())));
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("无法序列化消息图片路径", failure);
            }
        }
        return List.copyOf(snapshot);
    }

    private void insertMessages(
            String workspace, String sessionId, List<PersistedMessage> messages) {
        jdbc.batchUpdate(
                """
                INSERT INTO chat_messages(
                    workspace_id, session_id, position, message_id, role, content, timestamp,
                    image_paths_json, adopted, delivery_state,
                    input_tokens, cache_read_input_tokens, cache_write_input_tokens,
                    output_tokens, reasoning_tokens, model_calls, duration_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                messages,
                messages.size(),
                (statement, persisted) -> {
                    MessageSnapshot message = persisted.message();
                    statement.setString(1, workspace);
                    statement.setString(2, sessionId);
                    statement.setInt(3, persisted.position());
                    statement.setString(4, message.messageId());
                    statement.setString(5, message.role().name());
                    statement.setString(6, message.content());
                    statement.setString(7, message.timestamp().format(TIMESTAMP_FORMATTER));
                    statement.setString(8, persisted.imagePathsJson());
                    statement.setBoolean(9, message.adopted());
                    setNullableString(
                            statement,
                            10,
                            message.deliveryStatus() == null
                                    ? null
                                    : message.deliveryStatus().name());
                    TurnUsage usage = message.usage();
                    setNullableLong(statement, 11, usage == null ? null : usage.inputTokens());
                    setNullableLong(statement, 12, usage == null ? null : usage.cacheReadInputTokens());
                    setNullableLong(statement, 13, usage == null ? null : usage.cacheWriteInputTokens());
                    setNullableLong(statement, 14, usage == null ? null : usage.outputTokens());
                    setNullableLong(statement, 15, usage == null ? null : usage.reasoningTokens());
                    setNullableLong(statement, 16, usage == null ? null : usage.modelCalls());
                    setNullableLong(statement, 17, usage == null ? null : usage.durationMs());
                });
    }

    private void deleteRemovedSessions(String workspace, List<SessionSnapshot> sessions) {
        List<String> retained = sessions.stream().map(SessionSnapshot::id).toList();
        List<String> removed =
                jdbc.queryForList(
                                "SELECT id FROM chat_sessions WHERE workspace_id = ?",
                                String.class,
                                workspace)
                        .stream()
                        .filter(id -> !retained.contains(id))
                        .toList();
        deleteSessions(workspace, removed);
    }

    private void deleteSessions(String workspace, List<String> sessionIds) {
        if (sessionIds.isEmpty()) return;
        jdbc.batchUpdate(
                """
                DELETE FROM conversation_context_summary
                WHERE workspace_id = ? AND session_id = ?
                """,
                sessionIds,
                sessionIds.size(),
                (statement, id) -> {
                    statement.setString(1, workspace);
                    statement.setString(2, id);
                });
        jdbc.batchUpdate(
                """
                DELETE FROM chat_messages
                WHERE workspace_id = ? AND session_id = ?
                """,
                sessionIds,
                sessionIds.size(),
                (statement, id) -> {
                    statement.setString(1, workspace);
                    statement.setString(2, id);
                });
        jdbc.batchUpdate(
                """
                DELETE FROM chat_sessions
                WHERE workspace_id = ? AND id = ?
                """,
                sessionIds,
                sessionIds.size(),
                (statement, id) -> {
                    statement.setString(1, workspace);
                    statement.setString(2, id);
                });
    }

    private void ensureSessionRow(String workspace, String sessionId) {
        jdbc.update(
                """
                MERGE INTO chat_sessions(workspace_id, id, title, created_at, updated_at)
                KEY(workspace_id, id)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                workspace,
                sessionId,
                sessionId,
                LocalDateTime.now().format(TIMESTAMP_FORMATTER));
    }

    private List<String> readStringList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return json.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception failure) {
            log.warn("解析消息图片路径失败，使用空列表", failure);
            return Collections.emptyList();
        }
    }

    private static String requireWorkspaceId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("工作区 ID 不能为空");
        }
        return value;
    }

    private static String requireSessionId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        return value;
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static void setNullableString(
            java.sql.PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setNullableLong(
            java.sql.PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }

    private record PersistedMessage(
            int position, MessageSnapshot message, String imagePathsJson) {}
}
