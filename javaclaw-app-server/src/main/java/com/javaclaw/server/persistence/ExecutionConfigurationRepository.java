package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

/** 安装、Workspace 与 Thread 执行配置 SQL；调用者持有事务并负责作用域授权。 */
final class ExecutionConfigurationRepository {
    private final CanonicalJson json;
    private final Namespace namespace;

    /** 独立配置用途不能互相参与继承；最近选择仅用于客户端初始化新的 Thread。 */
    enum Namespace {
        EXECUTION,
        SUBAGENT,
        RECENT
    }

    ExecutionConfigurationRepository(CanonicalJson json) {
        this(json, Namespace.EXECUTION);
    }

    ExecutionConfigurationRepository(CanonicalJson json, Namespace namespace) {
        this.json = json;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    Optional<ExecutionConfiguration> find(
            Connection connection, Optional<WorkspaceId> workspaceId, Optional<ThreadId> threadId, boolean lock)
            throws SQLException {
        String sql = "SELECT WORKSPACE_ID, THREAD_ID, PAYLOAD, REVISION, UPDATED_AT "
                + "FROM CORE.EXECUTION_CONFIGURATION WHERE SCOPE_KEY = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, scopeKey(workspaceId, threadId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    void insert(Connection connection, ExecutionConfiguration value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.EXECUTION_CONFIGURATION
                    (SCOPE_KEY, WORKSPACE_ID, THREAD_ID, PAYLOAD, REVISION, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, scopeKey(value.workspaceId(), value.threadId()));
            statement.setString(
                    2, value.workspaceId().map(WorkspaceId::toString).orElse(null));
            statement.setString(3, value.threadId().map(ThreadId::toString).orElse(null));
            statement.setString(4, json.encode(value.overrides()).json());
            statement.setLong(5, value.revision());
            statement.setObject(6, value.updatedAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    void update(Connection connection, ExecutionConfiguration value, long expectedRevision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.EXECUTION_CONFIGURATION SET PAYLOAD = ?, REVISION = ?, UPDATED_AT = ?
                WHERE SCOPE_KEY = ? AND REVISION = ?
                """)) {
            statement.setString(1, json.encode(value.overrides()).json());
            statement.setLong(2, value.revision());
            statement.setObject(3, value.updatedAt().atOffset(ZoneOffset.UTC));
            statement.setString(4, scopeKey(value.workspaceId(), value.threadId()));
            statement.setLong(5, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw PersistenceException.revisionConflict("执行配置 revision 已改变");
            }
        }
    }

    private ExecutionConfiguration map(ResultSet result) throws SQLException {
        return new ExecutionConfiguration(
                Optional.ofNullable(result.getString("WORKSPACE_ID")).map(WorkspaceId::parse),
                Optional.ofNullable(result.getString("THREAD_ID")).map(ThreadId::parse),
                json.decode(new CanonicalPayload(result.getString("PAYLOAD")), ExecutionOverrides.class),
                result.getLong("REVISION"),
                result.getObject("UPDATED_AT", OffsetDateTime.class).toInstant());
    }

    private String scopeKey(Optional<WorkspaceId> workspaceId, Optional<ThreadId> threadId) {
        String prefix =
                switch (namespace) {
                    case EXECUTION -> "";
                    case SUBAGENT -> "subagent:";
                    case RECENT -> "recent:";
                };
        return prefix
                + threadId.map(value -> "thread:" + value)
                        .orElseGet(() ->
                                workspaceId.map(value -> "workspace:" + value).orElse("installation"));
    }
}
