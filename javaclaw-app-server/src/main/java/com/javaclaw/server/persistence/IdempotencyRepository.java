package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;

/** 持久命令结果的 SQL 与身份冲突校验。 */
public final class IdempotencyRepository {
    /** 创建共享命令结果 Repository。 */
    public IdempotencyRepository() {}

    /**
     * 按幂等键读取已提交结果。
     *
     * @param connection 当前事务连接
     * @param idempotencyKey 幂等键
     * @return 已提交结果
     * @throws SQLException 查询失败
     */
    public Optional<StoredCommand> find(Connection connection, String idempotencyKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT METHOD_NAME, REQUEST_DIGEST, RESPONSE_PAYLOAD, CREATED_AT
                FROM CORE.COMMAND_RESULT WHERE IDEMPOTENCY_KEY = ?
                """)) {
            statement.setString(1, idempotencyKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(idempotencyKey, result)) : Optional.empty();
            }
        }
    }

    /**
     * 写入不可变命令结果。
     *
     * @param connection 当前事务连接
     * @param identity 命令身份
     * @param response 规范响应
     * @param createdAt 创建时间
     * @throws SQLException 写入失败或幂等键冲突
     */
    public void insert(Connection connection, CommandIdentity identity, CanonicalPayload response, Instant createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.COMMAND_RESULT (
                    IDEMPOTENCY_KEY, METHOD_NAME, REQUEST_DIGEST, RESPONSE_PAYLOAD, CREATED_AT
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, identity.idempotencyKey());
            statement.setString(2, identity.method());
            statement.setString(3, identity.requestDigest());
            statement.setString(4, response.json());
            statement.setObject(5, createdAt.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private StoredCommand map(String idempotencyKey, ResultSet result) throws SQLException {
        return new StoredCommand(
                idempotencyKey,
                result.getString("METHOD_NAME"),
                result.getString("REQUEST_DIGEST"),
                new CanonicalPayload(result.getString("RESPONSE_PAYLOAD")),
                result.getObject("CREATED_AT", OffsetDateTime.class).toInstant());
    }

    /**
     * 已提交命令结果。
     *
     * @param idempotencyKey 幂等键
     * @param method 方法名
     * @param requestDigest 请求摘要
     * @param response 规范响应
     * @param createdAt 创建时间
     */
    public record StoredCommand(
            String idempotencyKey, String method, String requestDigest, CanonicalPayload response, Instant createdAt) {}
}
