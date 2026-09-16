package com.javaclaw.server.security.grant;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Grant;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Preview;
import com.javaclaw.builtin.contracts.BrowserGrantContracts.Snapshot;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.PersistenceException;

/** 浏览器来源授权的不可变历史、已签发预览与唯一 Turn 快照固定 SQL。 */
final class BrowserGrantRepository {
    private final CanonicalJson json;

    BrowserGrantRepository(CanonicalJson json) {
        this.json = json;
    }

    void requireThread(Connection connection, WorkspaceId workspaceId, ThreadId threadId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT ID FROM CORE.AGENT_THREAD WHERE ID=? AND WORKSPACE_ID=? FOR UPDATE")) {
            statement.setString(1, threadId.toString());
            statement.setString(2, workspaceId.toString());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw PersistenceException.invalidRequest("浏览器授权的 Workspace 与 Thread 不匹配");
                }
            }
        }
    }

    Instant requireTurn(Connection connection, WorkspaceId workspaceId, ThreadId threadId, TurnId turnId)
            throws SQLException {
        requireThread(connection, workspaceId, threadId);
        try (var statement =
                connection.prepareStatement("SELECT CREATED_AT FROM CORE.AGENT_TURN WHERE ID=? AND THREAD_ID=?")) {
            statement.setString(1, turnId.toString());
            statement.setString(2, threadId.toString());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw PersistenceException.invalidRequest("浏览器快照的 Turn 不属于当前 Thread");
                }
                return result.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    boolean insertPreview(Connection connection, Preview preview) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.BROWSER_ORIGIN_PREVIEW(DIGEST,WORKSPACE_ID,THREAD_ID,PAYLOAD,EXPIRES_AT)
                SELECT ?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM CORE.BROWSER_ORIGIN_PREVIEW WHERE DIGEST=?)
                """)) {
            statement.setString(1, preview.digest());
            statement.setString(2, preview.workspaceId().toString());
            statement.setString(3, preview.threadId().toString());
            statement.setString(4, json.encode(preview).json());
            statement.setObject(5, preview.expiresAt().atOffset(ZoneOffset.UTC));
            statement.setString(6, preview.digest());
            return statement.executeUpdate() == 1;
        }
    }

    void consumePreview(Connection connection, Preview preview) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT PAYLOAD,CONSUMED FROM CORE.BROWSER_ORIGIN_PREVIEW WHERE DIGEST=? FOR UPDATE")) {
            statement.setString(1, preview.digest());
            try (var result = statement.executeQuery()) {
                if (!result.next()
                        || result.getBoolean(2)
                        || !json.decode(new CanonicalPayload(result.getString(1)), Preview.class)
                                .equals(preview)) {
                    throw PersistenceException.invalidRequest("浏览器授权预览未签发、已确认或内容不匹配");
                }
            }
        }
        try (var statement =
                connection.prepareStatement("UPDATE CORE.BROWSER_ORIGIN_PREVIEW SET CONSUMED=TRUE WHERE DIGEST=?")) {
            statement.setString(1, preview.digest());
            statement.executeUpdate();
        }
    }

    Instant grantTime(Connection connection, ThreadId threadId, Instant now) throws SQLException {
        try (var statement =
                connection.prepareStatement("SELECT MAX(CREATED_AT) FROM CORE.AGENT_TURN WHERE THREAD_ID=?")) {
            statement.setString(1, threadId.toString());
            try (var result = statement.executeQuery()) {
                result.next();
                OffsetDateTime latest = result.getObject(1, OffsetDateTime.class);
                if (latest == null || latest.toInstant().isBefore(now)) {
                    return now;
                }
                // 墙上时钟回拨不能让后确认的来源伪装成已有 Turn 创建之前的授权。
                return latest.toInstant().plusNanos(1_000);
            }
        }
    }

    void insertGrant(Connection connection, Grant grant) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.BROWSER_ORIGIN_GRANT
                (ID,REVISION,STATE,WORKSPACE_ID,THREAD_ID,ORIGIN,PAYLOAD,CREATED_AT) VALUES(?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, grant.id());
            statement.setLong(2, grant.revision());
            statement.setString(3, grant.state().name());
            statement.setString(4, grant.workspaceId().toString());
            statement.setString(5, grant.threadId().toString());
            statement.setString(6, grant.origin().toString());
            statement.setString(7, json.encode(grant).json());
            statement.setObject(8, grant.createdAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    Optional<Grant> latest(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM CORE.BROWSER_ORIGIN_GRANT WHERE ID=? ORDER BY REVISION DESC LIMIT 1")) {
            statement.setString(1, id);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(decodeGrant(result)) : Optional.empty();
            }
        }
    }

    List<Grant> latestAt(Connection connection, ThreadId threadId, Instant before) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT G.* FROM CORE.BROWSER_ORIGIN_GRANT G
                JOIN (SELECT ID,MAX(REVISION) REVISION FROM CORE.BROWSER_ORIGIN_GRANT
                      WHERE THREAD_ID=? AND CREATED_AT < ? GROUP BY ID) L
                ON G.ID=L.ID AND G.REVISION=L.REVISION ORDER BY G.CREATED_AT DESC,G.ID
                """)) {
            statement.setString(1, threadId.toString());
            statement.setObject(2, before.atOffset(ZoneOffset.UTC));
            try (var result = statement.executeQuery()) {
                List<Grant> grants = new ArrayList<>();
                while (result.next()) {
                    grants.add(decodeGrant(result));
                }
                return List.copyOf(grants);
            }
        }
    }

    Optional<Snapshot> snapshot(Connection connection, TurnId turnId) throws SQLException {
        try (var statement =
                connection.prepareStatement("SELECT * FROM CORE.BROWSER_TURN_GRANT_SNAPSHOT WHERE TURN_ID=?")) {
            statement.setString(1, turnId.toString());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                Snapshot snapshot = json.decode(new CanonicalPayload(result.getString("PAYLOAD")), Snapshot.class);
                if (!snapshot.turnId().equals(turnId)
                        || !snapshot.snapshotId().equals(result.getString("SNAPSHOT_ID"))
                        || !snapshot.workspaceId().toString().equals(result.getString("WORKSPACE_ID"))
                        || !snapshot.threadId().toString().equals(result.getString("THREAD_ID"))) {
                    throw new PersistenceException("浏览器授权快照行身份不一致");
                }
                return Optional.of(snapshot);
            }
        }
    }

    void insertSnapshot(Connection connection, Snapshot snapshot) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.BROWSER_TURN_GRANT_SNAPSHOT(TURN_ID,SNAPSHOT_ID,WORKSPACE_ID,THREAD_ID,PAYLOAD)
                VALUES(?,?,?,?,?)
                """)) {
            statement.setString(1, snapshot.turnId().toString());
            statement.setString(2, snapshot.snapshotId());
            statement.setString(3, snapshot.workspaceId().toString());
            statement.setString(4, snapshot.threadId().toString());
            statement.setString(5, json.encode(snapshot).json());
            statement.executeUpdate();
        }
    }

    void audit(Connection connection, BrowserGrantService.Scope scope, BrowserGrantService.Decision decision)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.BROWSER_GRANT_DECISION(ID,WORKSPACE_ID,THREAD_ID,PAYLOAD,DECIDED_AT)
                VALUES(?,?,?,?,?)
                """)) {
            statement.setString(1, decision.id());
            statement.setString(2, scope.workspaceId().toString());
            statement.setString(3, scope.threadId().toString());
            statement.setString(4, json.encode(decision).json());
            statement.setObject(5, decision.decidedAt().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    List<BrowserGrantService.Decision> audit(Connection connection, ThreadId threadId, int limit) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT PAYLOAD FROM CORE.BROWSER_GRANT_DECISION WHERE THREAD_ID=?
                ORDER BY DECIDED_AT DESC,ID DESC LIMIT ?
                """)) {
            statement.setString(1, threadId.toString());
            statement.setInt(2, limit);
            try (var result = statement.executeQuery()) {
                List<BrowserGrantService.Decision> decisions = new ArrayList<>();
                while (result.next()) {
                    decisions.add(
                            json.decode(new CanonicalPayload(result.getString(1)), BrowserGrantService.Decision.class));
                }
                return List.copyOf(decisions);
            }
        }
    }

    private Grant decodeGrant(java.sql.ResultSet row) throws SQLException {
        Grant grant = json.decode(new CanonicalPayload(row.getString("PAYLOAD")), Grant.class);
        if (!grant.id().equals(row.getString("ID"))
                || grant.revision() != row.getLong("REVISION")
                || !grant.state().name().equals(row.getString("STATE"))
                || !grant.workspaceId().toString().equals(row.getString("WORKSPACE_ID"))
                || !grant.threadId().toString().equals(row.getString("THREAD_ID"))
                || !grant.origin().toString().equals(row.getString("ORIGIN"))
                || !grant.createdAt()
                        .equals(row.getObject("CREATED_AT", OffsetDateTime.class)
                                .toInstant())) {
            throw new PersistenceException("浏览器授权行身份与内容不一致");
        }
        return grant;
    }
}
