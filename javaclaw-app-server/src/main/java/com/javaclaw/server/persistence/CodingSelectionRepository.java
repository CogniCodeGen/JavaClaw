package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.EnvironmentSpec;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.toolchain.CodingEnvironmentSelection;

/** 内部环境 sidecar 绑定唯一的工具目录捕获 ID；不会改变公开 AutomationExecutionSnapshot。 */
final class CodingSelectionRepository {
    private final CanonicalJson json;
    private final Clock clock;

    CodingSelectionRepository(CanonicalJson json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    CodingEnvironmentSelection frozen(Connection connection, TurnId turn) throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT ENVIRONMENT_JSON,ENVIRONMENT_DIGEST,SELECTION_JSON,SELECTION_DIGEST
                FROM CORE.TURN_CODING_ENVIRONMENT WHERE TURN_ID = ?
                """)) {
            statement.setString(1, turn.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return unavailable();
                }
                CanonicalPayload environment = checked(rows.getString(1), rows.getString(2));
                if (rows.getString(3) == null) {
                    return new CodingEnvironmentSelection(
                                    json.decode(environment, Environment.class), Map.of(), Map.of(), false)
                            .inherited();
                }
                var selection =
                        json.decode(checked(rows.getString(3), rows.getString(4)), CodingEnvironmentSelection.class);
                if (!selection.environment().equals(json.decode(environment, Environment.class))) {
                    throw new SecurityException("Coding 选择与环境快照不一致");
                }
                return selection.inherited();
            }
        }
    }

    void save(Connection connection, TurnId snapshotId, WorkspaceId workspace, CodingEnvironmentSelection selection)
            throws Exception {
        Optional<CodingEnvironmentSelection> existing = find(connection, snapshotId, workspace);
        if (existing.isPresent()) {
            if (!existing.orElseThrow().equals(selection.inherited())) {
                throw new SecurityException("相同 Execution 不能替换冻结 Coding 选择");
            }
            return;
        }
        var payload = json.encode(selection.inherited());
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.CODING_EXECUTION_ENVIRONMENT
                (SNAPSHOT_ID,WORKSPACE_ID,SELECTION_JSON,SELECTION_DIGEST,CREATED_AT) VALUES (?,?,?,?,?)
                """)) {
            statement.setString(1, snapshotId.toString());
            statement.setString(2, workspace.toString());
            statement.setString(3, payload.json());
            statement.setString(4, payload.sha256());
            statement.setObject(5, clock.instant().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    Optional<CodingEnvironmentSelection> find(Connection connection, TurnId snapshotId, WorkspaceId workspace)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT WORKSPACE_ID,SELECTION_JSON,SELECTION_DIGEST FROM CORE.CODING_EXECUTION_ENVIRONMENT
                WHERE SNAPSHOT_ID = ?
                """)) {
            statement.setString(1, snapshotId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                if (!workspace.toString().equals(rows.getString(1))) {
                    throw new SecurityException("Execution Coding 快照属于其他 Workspace");
                }
                return Optional.of(
                        json.decode(checked(rows.getString(2), rows.getString(3)), CodingEnvironmentSelection.class)
                                .inherited());
            }
        }
    }

    private static CanonicalPayload checked(String text, String digest) {
        CanonicalPayload payload = new CanonicalPayload(text);
        if (!payload.sha256().equals(digest)) {
            throw new SecurityException("Coding 选择快照摘要损坏");
        }
        return payload;
    }

    static CodingEnvironmentSelection unavailable() {
        Environment environment =
                new Environment(0, new EnvironmentSpec("历史执行：缺少工具链快照", java.util.List.of(), java.util.Set.of(), false));
        return new CodingEnvironmentSelection(environment, Map.of(), Map.of(), false).inherited();
    }
}
