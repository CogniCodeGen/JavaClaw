package com.javaclaw.server.security.grant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionDecisionTrace;
import com.javaclaw.api.SecurityGrantKind;
import com.javaclaw.api.WorkspaceId;

/** 两类安全授权历史、调用账本与决策审计的固定 SQL。 */
final class SecurityGrantRepository {
    void insertGrant(Connection connection, GrantTable table, StoredGrant grant) throws SQLException {
        String sql = "INSERT INTO " + table.sqlName()
                + " (ID, REVISION, STATE, WORKSPACE_ID, PAYLOAD, CREATED_AT, UPDATED_AT)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, grant.id());
            statement.setLong(2, grant.revision());
            statement.setString(3, grant.state());
            statement.setString(4, grant.workspaceId().toString());
            statement.setString(5, grant.payload().json());
            statement.setObject(6, grant.createdAt().atOffset(ZoneOffset.UTC));
            statement.setObject(7, grant.updatedAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    Optional<StoredGrant> latest(Connection connection, GrantTable table, String id, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        String sql = selectColumns(table) + " WHERE ID = ? ORDER BY REVISION DESC LIMIT 1" + suffix;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapGrant(result)) : Optional.empty();
            }
        }
    }

    List<StoredGrant> listLatest(Connection connection, GrantTable table, WorkspaceId workspaceId) throws SQLException {
        String sql = selectColumns("G", table) + " JOIN (SELECT ID, MAX(REVISION) REVISION FROM "
                + table.sqlName()
                + " GROUP BY ID) L ON L.ID = G.ID AND L.REVISION = G.REVISION"
                + " WHERE G.WORKSPACE_ID = ? ORDER BY G.ID";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, workspaceId.toString());
            return readGrants(statement);
        }
    }

    List<StoredGrant> history(Connection connection, GrantTable table, String id) throws SQLException {
        String sql = selectColumns(table) + " WHERE ID = ? ORDER BY REVISION";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            return readGrants(statement);
        }
    }

    boolean workspaceExists(Connection connection, WorkspaceId workspaceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM CORE.WORKSPACE WHERE ID = ?")) {
            statement.setString(1, workspaceId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    boolean lockWorkspace(Connection connection, WorkspaceId workspaceId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT ID FROM CORE.WORKSPACE WHERE ID = ? FOR UPDATE")) {
            statement.setString(1, workspaceId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    int countUses(Connection connection, String grantId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT COUNT(*) FROM CORE.UNATTENDED_TOOL_USE WHERE GRANT_ID = ?")) {
            statement.setString(1, grantId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    Optional<StoredUse> findUse(Connection connection, String grantId, String invocationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT GRANT_REVISION, OUTCOME, CONSUMED_AT, UPDATED_AT
                FROM CORE.UNATTENDED_TOOL_USE
                WHERE GRANT_ID = ? AND INVOCATION_ID = ?
                """)) {
            statement.setString(1, grantId);
            statement.setString(2, invocationId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(mapUse(grantId, invocationId, result)) : Optional.empty();
            }
        }
    }

    void insertUse(Connection connection, String grantId, String invocationId, long revision, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.UNATTENDED_TOOL_USE (
                    GRANT_ID, INVOCATION_ID, GRANT_REVISION, OUTCOME, CONSUMED_AT, UPDATED_AT
                ) VALUES (?, ?, ?, 'RESERVED', ?, ?)
                """)) {
            statement.setString(1, grantId);
            statement.setString(2, invocationId);
            statement.setLong(3, revision);
            statement.setObject(4, now.atOffset(ZoneOffset.UTC));
            statement.setObject(5, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    int updateOutcome(Connection connection, String grantId, String invocationId, String outcome, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.UNATTENDED_TOOL_USE SET OUTCOME = ?, UPDATED_AT = ?
                WHERE GRANT_ID = ? AND INVOCATION_ID = ? AND OUTCOME = 'RESERVED'
                """)) {
            statement.setString(1, outcome);
            statement.setObject(2, now.atOffset(ZoneOffset.UTC));
            statement.setString(3, grantId);
            statement.setString(4, invocationId);
            return statement.executeUpdate();
        }
    }

    int markReservedUnknown(Connection connection, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE CORE.UNATTENDED_TOOL_USE SET OUTCOME = 'UNKNOWN_OUTCOME', UPDATED_AT = ?
                WHERE OUTCOME = 'RESERVED'
                """)) {
            statement.setObject(1, now.atOffset(ZoneOffset.UTC));
            return statement.executeUpdate();
        }
    }

    void insertTrace(Connection connection, PermissionDecisionTrace trace, CanonicalPayload payload)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO CORE.PERMISSION_DECISION_TRACE (
                    ID, WORKSPACE_ID, GRANT_KIND, GRANT_ID, ALLOWED, PAYLOAD, DECIDED_AT
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, trace.id());
            statement.setString(2, trace.workspaceId().toString());
            statement.setString(3, trace.grantKind().name());
            statement.setString(4, trace.grantId());
            statement.setBoolean(5, trace.allowed());
            statement.setString(6, payload.json());
            statement.setObject(7, trace.decidedAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    List<CanonicalPayload> listTraces(
            Connection connection,
            WorkspaceId workspaceId,
            Optional<SecurityGrantKind> kind,
            Optional<String> grantId,
            int limit)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT PAYLOAD FROM CORE.PERMISSION_DECISION_TRACE
                WHERE WORKSPACE_ID = ?
                  AND (? IS NULL OR GRANT_KIND = ?)
                  AND (? IS NULL OR GRANT_ID = ?)
                ORDER BY DECIDED_AT DESC, DECISION_SEQUENCE DESC LIMIT ?
                """)) {
            statement.setString(1, workspaceId.toString());
            bindOptional(statement, 2, kind.map(Enum::name));
            bindOptional(statement, 4, grantId);
            statement.setInt(6, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<CanonicalPayload> traces = new ArrayList<>();
                while (result.next()) {
                    traces.add(new CanonicalPayload(result.getString(1)));
                }
                return List.copyOf(traces);
            }
        }
    }

    private static void bindOptional(PreparedStatement statement, int firstIndex, Optional<String> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setString(firstIndex, value.orElseThrow());
            statement.setString(firstIndex + 1, value.orElseThrow());
        } else {
            statement.setNull(firstIndex, Types.VARCHAR);
            statement.setNull(firstIndex + 1, Types.VARCHAR);
        }
    }

    private static String selectColumns(GrantTable table) {
        return "SELECT ID, REVISION, STATE, WORKSPACE_ID, PAYLOAD, CREATED_AT, UPDATED_AT FROM " + table.sqlName();
    }

    private static String selectColumns(String alias, GrantTable table) {
        return "SELECT G.ID, G.REVISION, G.STATE, G.WORKSPACE_ID, G.PAYLOAD, G.CREATED_AT, G.UPDATED_AT FROM "
                + table.sqlName()
                + ' '
                + alias;
    }

    private static List<StoredGrant> readGrants(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            List<StoredGrant> grants = new ArrayList<>();
            while (result.next()) {
                grants.add(mapGrant(result));
            }
            return List.copyOf(grants);
        }
    }

    private static StoredGrant mapGrant(ResultSet result) throws SQLException {
        return new StoredGrant(
                result.getString("ID"),
                result.getLong("REVISION"),
                result.getString("STATE"),
                WorkspaceId.parse(result.getString("WORKSPACE_ID")),
                new CanonicalPayload(result.getString("PAYLOAD")),
                instant(result, "CREATED_AT"),
                instant(result, "UPDATED_AT"));
    }

    private static StoredUse mapUse(String grantId, String invocationId, ResultSet result) throws SQLException {
        return new StoredUse(
                grantId,
                invocationId,
                result.getLong("GRANT_REVISION"),
                result.getString("OUTCOME"),
                instant(result, "CONSUMED_AT"),
                instant(result, "UPDATED_AT"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getObject(column, OffsetDateTime.class).toInstant();
    }

    enum GrantTable {
        PRIVATE_NETWORK("CORE.PRIVATE_NETWORK_GRANT"),
        UNATTENDED_TOOL("CORE.UNATTENDED_TOOL_GRANT");

        private final String sqlName;

        GrantTable(String sqlName) {
            this.sqlName = sqlName;
        }

        String sqlName() {
            return sqlName;
        }
    }

    record StoredGrant(
            String id,
            long revision,
            String state,
            WorkspaceId workspaceId,
            CanonicalPayload payload,
            Instant createdAt,
            Instant updatedAt) {}

    record StoredUse(
            String grantId,
            String invocationId,
            long grantRevision,
            String outcome,
            Instant consumedAt,
            Instant updatedAt) {}
}
