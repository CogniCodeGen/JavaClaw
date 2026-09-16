package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Catalog;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Registry;
import com.javaclaw.builtin.contracts.CodingSystemContracts.RegistryUpdate;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.system.SystemCommandCatalog;

/** 系统登记和不可变快照的事务边界；不执行外部文件读取或项目进程。 */
final class CodingSystemRepository {
    private final CanonicalJson json;
    private final Clock clock;

    CodingSystemRepository(CanonicalJson json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    Registry read(Connection connection, WorkspaceId workspace) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT REVISION,REGISTRY_JSON FROM CORE.CODING_SYSTEM_REGISTRY WHERE WORKSPACE_ID=?")) {
            statement.setString(1, workspace.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return new Registry(0, List.of());
                }
                Registry result = json.decode(new CanonicalPayload(rows.getString(2)), Registry.class);
                if (result.revision() != rows.getLong(1)) {
                    throw new SecurityException("系统登记版本与内容不一致");
                }
                return result;
            }
        }
    }

    Registry update(Connection connection, WorkspaceId workspace, CommandIdentity identity, RegistryUpdate request)
            throws SQLException {
        lockWorkspace(connection, workspace);
        IdempotencyRepository commands = new IdempotencyRepository();
        var previous = commands.find(connection, identity.idempotencyKey());
        if (previous.isPresent()) {
            var stored = previous.orElseThrow();
            if (!stored.method().equals(identity.method())
                    || !stored.requestDigest().equals(identity.requestDigest())) {
                throw PersistenceException.idempotencyConflict("系统登记幂等键被其他请求使用");
            }
            return json.decode(stored.response(), Registry.class);
        }
        SystemRegistrationValidation.requirePlatform(request.registrations(), SystemCommandCatalog.platform());
        Registry current = read(connection, workspace);
        if (current.revision() != identity.expectedRevision()) {
            throw PersistenceException.revisionConflict("系统程序登记版本已变化");
        }
        Registry next = new Registry(Math.addExact(current.revision(), 1), request.registrations());
        try (var statement = connection.prepareStatement("""
                MERGE INTO CORE.CODING_SYSTEM_REGISTRY (WORKSPACE_ID,REVISION,REGISTRY_JSON,UPDATED_AT)
                KEY(WORKSPACE_ID) VALUES (?,?,?,?)
                """)) {
            statement.setString(1, workspace.toString());
            statement.setLong(2, next.revision());
            statement.setString(3, json.encode(next).json());
            statement.setObject(4, clock.instant().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
        commands.insert(connection, identity, json.encode(next), clock.instant());
        return next;
    }

    void freeze(Connection connection, AgentTurn turn, CodingSystemSelection selection, Instant now)
            throws SQLException {
        if (selection.expectedRegistryRevision().isPresent()) {
            WorkspaceId workspace = new ThreadRepository()
                    .find(connection, turn.threadId())
                    .orElseThrow()
                    .workspaceId();
            lockWorkspace(connection, workspace);
            if (read(connection, workspace).revision()
                    != selection.expectedRegistryRevision().orElseThrow()) {
                throw PersistenceException.revisionConflict("系统程序发现后登记已改变，请重新启动 Turn");
            }
        }
        CanonicalPayload payload = json.encode(selection.catalog());
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.TURN_SYSTEM_ENVIRONMENT (TURN_ID,CATALOG_JSON,CATALOG_DIGEST,CREATED_AT)
                VALUES (?,?,?,?)
                """)) {
            statement.setString(1, turn.id().toString());
            statement.setString(2, payload.json());
            statement.setString(3, payload.sha256());
            statement.setObject(4, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    Catalog frozen(Connection connection, TurnId turn) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT CATALOG_JSON,CATALOG_DIGEST FROM CORE.TURN_SYSTEM_ENVIRONMENT WHERE TURN_ID=?")) {
            statement.setString(1, turn.toString());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? decode(rows.getString(1), rows.getString(2)) : unavailable();
            }
        }
    }

    Optional<Catalog> execution(Connection connection, TurnId snapshot, WorkspaceId workspace) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT WORKSPACE_ID,CATALOG_JSON,CATALOG_DIGEST FROM CORE.CODING_EXECUTION_SYSTEM_ENVIRONMENT
                WHERE SNAPSHOT_ID=?
                """)) {
            statement.setString(1, snapshot.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                if (!rows.getString(1).equals(workspace.toString())) {
                    throw new SecurityException("系统执行快照属于其他 Workspace");
                }
                return Optional.of(decode(rows.getString(2), rows.getString(3)));
            }
        }
    }

    void saveExecution(Connection connection, TurnId snapshot, WorkspaceId workspace, CodingSystemSelection selection)
            throws SQLException {
        lockWorkspace(connection, workspace);
        Catalog catalog = selection.catalog();
        var existing = execution(connection, snapshot, workspace);
        if (existing.isPresent()) {
            if (!existing.orElseThrow().equals(catalog)) {
                throw new SecurityException("相同 Execution 不能替换系统程序快照");
            }
            return;
        }
        if (selection.expectedRegistryRevision().isPresent()
                && read(connection, workspace).revision()
                        != selection.expectedRegistryRevision().orElseThrow()) {
            throw PersistenceException.revisionConflict("系统程序发现后登记已变化，请重新捕获 Execution");
        }
        CanonicalPayload payload = json.encode(catalog);
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.CODING_EXECUTION_SYSTEM_ENVIRONMENT
                (SNAPSHOT_ID,WORKSPACE_ID,CATALOG_JSON,CATALOG_DIGEST,CREATED_AT) VALUES (?,?,?,?,?)
                """)) {
            statement.setString(1, snapshot.toString());
            statement.setString(2, workspace.toString());
            statement.setString(3, payload.json());
            statement.setString(4, payload.sha256());
            statement.setObject(5, clock.instant().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    void requireOwner(Connection connection, TurnId turn, WorkspaceId workspace) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT t.WORKSPACE_ID FROM CORE.AGENT_TURN a JOIN CORE.AGENT_THREAD t ON t.ID=a.THREAD_ID
                WHERE a.ID=?
                """)) {
            statement.setString(1, turn.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getString(1).equals(workspace.toString())) {
                    throw new SecurityException("父系统快照不属于当前 Workspace");
                }
            }
        }
    }

    private Catalog decode(String text, String digest) {
        CanonicalPayload payload = new CanonicalPayload(text);
        if (!payload.sha256().equals(digest)) {
            throw new SecurityException("系统程序快照摘要损坏");
        }
        return json.decode(payload, Catalog.class);
    }

    private static void lockWorkspace(Connection connection, WorkspaceId workspace) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT ID FROM CORE.WORKSPACE WHERE ID=? FOR UPDATE")) {
            statement.setString(1, workspace.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw PersistenceException.invalidRequest("系统登记所属 Workspace 不存在");
                }
            }
        }
    }

    static Catalog unavailable() {
        return new Catalog("unavailable", 0, List.of());
    }
}
