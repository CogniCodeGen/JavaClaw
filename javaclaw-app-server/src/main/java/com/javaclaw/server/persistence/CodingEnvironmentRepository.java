package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.Environment;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.EnvironmentSpec;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;

/** Workspace 环境版本和 Turn 的不可变快照；环境选择本身不授予文件、进程或网络权限。 */
public final class CodingEnvironmentRepository {
    private final H2Transactions transactions;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建环境仓库。
     *
     * @param database 已初始化的 data-v6
     * @param json 规范 JSON
     * @param clock 平台时钟
     */
    public CodingEnvironmentRepository(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(database);
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 读取当前配置；没有配置时返回发行清单的确定缺省版本。
     *
     * @param workspaceId 已验证的 Workspace
     * @return 当前环境
     */
    public Environment read(WorkspaceId workspaceId) {
        return execute(connection -> read(connection, workspaceId, json));
    }

    /**
     * 以乐观版本和幂等身份更新环境，新配置仅影响后续 Turn。
     *
     * @param workspaceId 已验证 Workspace
     * @param identity 管理命令身份
     * @param spec 已通过可信目录校验的配置
     * @return 更新后版本
     */
    public Environment update(WorkspaceId workspaceId, CommandIdentity identity, EnvironmentSpec spec) {
        return execute(connection -> {
            lockWorkspace(connection, workspaceId);
            IdempotencyRepository commands = new IdempotencyRepository();
            var previous = commands.find(connection, identity.idempotencyKey());
            if (previous.isPresent()) {
                var stored = previous.orElseThrow();
                if (!stored.method().equals(identity.method())
                        || !stored.requestDigest().equals(identity.requestDigest())) {
                    throw PersistenceException.idempotencyConflict("Coding 环境幂等键被不同请求使用");
                }
                return json.decode(stored.response(), Environment.class);
            }
            Environment current = read(connection, workspaceId, json);
            if (current.revision() != identity.expectedRevision()) {
                throw PersistenceException.revisionConflict("Coding 环境版本已改变");
            }
            Environment next = new Environment(current.revision() + 1, spec);
            try (var statement = connection.prepareStatement("""
                    MERGE INTO CORE.CODING_ENVIRONMENT (WORKSPACE_ID,REVISION,SPEC_JSON,UPDATED_AT)
                    KEY(WORKSPACE_ID) VALUES (?,?,?,?)
                    """)) {
                statement.setString(1, workspaceId.toString());
                statement.setLong(2, next.revision());
                statement.setString(3, json.encode(spec).json());
                statement.setObject(4, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
                statement.executeUpdate();
            }
            commands.insert(connection, identity, json.encode(next), clock.instant());
            return next;
        });
    }

    /**
     * 在创建 Turn 的同一事务中固定环境；恢复时禁止用当前配置替换。
     *
     * @param connection Turn 创建事务
     * @param turn 刚插入的 Turn
     * @param selection 事务外完成的权威选择
     * @param now 创建时间
     * @throws SQLException 缺少 Thread 或快照写入失败
     */
    static void freeze(
            Connection connection,
            AgentTurn turn,
            com.javaclaw.server.toolchain.CodingEnvironmentSelection selection,
            Instant now)
            throws SQLException {
        CanonicalJson json = new CanonicalJson();
        WorkspaceId workspaceId;
        try (var statement = connection.prepareStatement("SELECT WORKSPACE_ID FROM CORE.AGENT_THREAD WHERE ID=?")) {
            statement.setString(1, turn.threadId().toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("冻结 Coding 环境时 Thread 不存在");
                }
                workspaceId = WorkspaceId.parse(rows.getString(1));
            }
        }
        if (selection.expectedWorkspaceRevision().isPresent()) {
            lockWorkspace(connection, workspaceId);
            if (read(connection, workspaceId, json).revision()
                    != selection.expectedWorkspaceRevision().orElseThrow()) {
                throw PersistenceException.revisionConflict("准备项目声明后 Coding 环境已改变，请重新启动 Turn");
            }
        }
        CanonicalPayload payload = json.encode(selection.environment());
        CanonicalPayload evidence = json.encode(selection);
        try (var statement = connection.prepareStatement("""
                INSERT INTO CORE.TURN_CODING_ENVIRONMENT
                (TURN_ID,ENVIRONMENT_JSON,ENVIRONMENT_DIGEST,CREATED_AT,SELECTION_JSON,SELECTION_DIGEST) VALUES (?,?,?,?,?,?)
                """)) {
            statement.setString(1, turn.id().toString());
            statement.setString(2, payload.json());
            statement.setString(3, payload.sha256());
            statement.setObject(4, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            statement.setString(5, evidence.json());
            statement.setString(6, evidence.sha256());
            statement.executeUpdate();
        }
    }

    /**
     * 读取并验证当前 Turn 已冻结的环境。
     *
     * @param turnId 权威 Turn
     * @return 不可变环境快照
     */
    public Environment frozen(TurnId turnId) {
        return execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT ENVIRONMENT_JSON,ENVIRONMENT_DIGEST FROM CORE.TURN_CODING_ENVIRONMENT WHERE TURN_ID=?
                    """)) {
                statement.setString(1, turnId.toString());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new SecurityException("旧 Turn 没有冻结 Coding 环境，不能取得新增执行能力");
                    }
                    CanonicalPayload payload = new CanonicalPayload(rows.getString(1));
                    if (!payload.sha256().equals(rows.getString(2))) {
                        throw new SecurityException("Coding 环境快照摘要不一致");
                    }
                    return json.decode(payload, Environment.class);
                }
            }
        });
    }

    private static Environment read(Connection connection, WorkspaceId workspaceId, CanonicalJson json)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT REVISION,SPEC_JSON FROM CORE.CODING_ENVIRONMENT WHERE WORKSPACE_ID=? FOR UPDATE")) {
            statement.setString(1, workspaceId.toString());
            try (var rows = statement.executeQuery()) {
                return rows.next()
                        ? new Environment(
                                rows.getLong(1),
                                json.decode(new CanonicalPayload(rows.getString(2)), EnvironmentSpec.class))
                        : new Environment(0, CodingToolchainCatalog.bundled().defaultEnvironment());
            }
        }
    }

    private static void lockWorkspace(Connection connection, WorkspaceId workspaceId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT ID FROM CORE.WORKSPACE WHERE ID=? FOR UPDATE")) {
            statement.setString(1, workspaceId.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw PersistenceException.invalidRequest("Coding 环境所属 Workspace 不存在");
                }
            }
        }
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Coding 环境事务失败", failure);
        }
    }
}
