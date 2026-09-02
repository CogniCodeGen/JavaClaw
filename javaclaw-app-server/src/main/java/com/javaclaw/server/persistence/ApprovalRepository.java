package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.ApprovalRecord;
import com.javaclaw.api.ApprovalRequest;
import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnId;

/** Approval 行的条件创建、查询与单向终态更新。 */
final class ApprovalRepository {
    Optional<ApprovalRecord> find(Connection connection, String id) throws SQLException {
        return find(connection, id, false);
    }

    Optional<ApprovalRecord> lock(Connection connection, String id) throws SQLException {
        return find(connection, id, true);
    }

    List<ApprovalRecord> list(Connection connection, Optional<TurnId> turnId, boolean includeResolved)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM CORE.APPROVAL_REQUEST
                WHERE (? IS NULL OR TURN_ID = ?) AND (? OR STATE = 'PENDING')
                ORDER BY CREATED_AT, ID
                """)) {
            String turn = turnId.map(TurnId::toString).orElse(null);
            statement.setString(1, turn);
            statement.setString(2, turn);
            statement.setBoolean(3, includeResolved);
            try (ResultSet result = statement.executeQuery()) {
                List<ApprovalRecord> approvals = new ArrayList<>();
                while (result.next()) {
                    approvals.add(map(result));
                }
                return List.copyOf(approvals);
            }
        }
    }

    void insert(Connection connection, ApprovalRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.APPROVAL_REQUEST (
                    ID, TURN_ID, CALL_ID, PRODUCER_ID, TOOL_NAME, TOOL_REVISION, RISK,
                    REQUEST_DIGEST, EXPLANATION, STATE, REVISION, RESOLUTION_REASON,
                    CREATED_AT, EXPIRES_AT, UPDATED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 1, NULL, ?, ?, ?)
                """)) {
            statement.setString(1, request.id());
            statement.setString(2, request.turnId().toString());
            statement.setString(3, request.callId());
            statement.setString(4, request.tool().producerId());
            statement.setString(5, request.tool().name());
            statement.setLong(6, request.tool().revision());
            statement.setString(7, request.risk().name());
            statement.setString(8, request.requestDigest());
            statement.setString(9, request.explanation());
            statement.setObject(10, at(request.createdAt()));
            statement.setObject(11, at(request.expiresAt()));
            statement.setObject(12, at(request.createdAt()));
            statement.executeUpdate();
        }
    }

    ApprovalRecord resolve(
            Connection connection, String id, long expectedRevision, ApprovalState state, String reason, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.APPROVAL_REQUEST
                SET STATE = ?, REVISION = REVISION + 1, RESOLUTION_REASON = ?, UPDATED_AT = ?
                WHERE ID = ? AND STATE = 'PENDING' AND REVISION = ?
                """)) {
            statement.setString(1, state.name());
            statement.setString(2, reason);
            statement.setObject(3, at(now));
            statement.setString(4, id);
            statement.setLong(5, expectedRevision);
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("审批 revision 或状态已经改变");
            }
        }
        return find(connection, id).orElseThrow();
    }

    ApprovalRecord revokeApproved(Connection connection, String id, String reason, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.APPROVAL_REQUEST
                SET STATE = 'REVOKED', REVISION = REVISION + 1, RESOLUTION_REASON = ?, UPDATED_AT = ?
                WHERE ID = ? AND STATE = 'APPROVED'
                """)) {
            statement.setString(1, reason);
            statement.setObject(2, at(now));
            statement.setString(3, id);
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("只有已批准请求可以标记为撤权");
            }
        }
        return find(connection, id).orElseThrow();
    }

    private Optional<ApprovalRecord> find(Connection connection, String id, boolean lock) throws SQLException {
        String sql = "SELECT * FROM CORE.APPROVAL_REQUEST WHERE ID = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        }
    }

    private ApprovalRecord map(ResultSet result) throws SQLException {
        Instant createdAt = instant(result, "CREATED_AT");
        ApprovalRequest request = new ApprovalRequest(
                result.getString("ID"),
                TurnId.parse(result.getString("TURN_ID")),
                result.getString("CALL_ID"),
                new ToolIdentity(
                        result.getString("PRODUCER_ID"),
                        result.getString("TOOL_NAME"),
                        result.getLong("TOOL_REVISION")),
                ToolRisk.valueOf(result.getString("RISK")),
                result.getString("EXPLANATION"),
                result.getString("REQUEST_DIGEST"),
                createdAt,
                instant(result, "EXPIRES_AT"));
        return new ApprovalRecord(
                request,
                ApprovalState.valueOf(result.getString("STATE")),
                result.getLong("REVISION"),
                Optional.ofNullable(result.getString("RESOLUTION_REASON")),
                instant(result, "UPDATED_AT"));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }
}
