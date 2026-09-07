package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.nativehost.sandbox.SandboxIsolationFailure;
import com.javaclaw.protocol.CanonicalJson;

/** 原生权限恢复失败的持久隔离锁与审计证据。没有普通清除锁接口；重启、归档再启用或重新登记相同根都不能解除锁。 原始 ACL 凭据保留在平台私有目录，不通过模型结果暴露路径或 DACL。 */
public final class WorkspaceSecurityRepository {
    private final H2Transactions transactions;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建 Workspace 安全审计服务。
     *
     * @param database 私有 H2 数据库
     * @param json 规范 Codec
     * @param clock 审计时钟
     */
    public WorkspaceSecurityRepository(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(database);
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 仅可信异常类型可建立隔离锁；正常用户退出码或相似输出不构成安全事件。
     *
     * @param workspace 所属 Workspace
     * @param turn 当前 Turn；启动前的原生准备失败可为空
     * @param root 服务端冻结执行根
     * @param failure 原生异常及聚合清理失败
     * @return 是否识别并持久化恢复失败
     */
    public boolean quarantine(WorkspaceId workspace, Optional<TurnId> turn, Path root, Throwable failure) {
        var evidence = SandboxIsolationFailure.evidence(failure);
        if (evidence.isEmpty()) {
            return false;
        }
        var identity = new Evidence(workspace, turn, root.toAbsolutePath().normalize(), evidence.orElseThrow());
        var payload = json.encode(identity);
        execute(connection -> {
            lockWorkspace(connection, workspace);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO CORE.WORKSPACE_SECURITY_EVENT
                    (ID,WORKSPACE_ID,TURN_ID,EXECUTION_ROOT,CODE,EVIDENCE_JSON,CREATED_AT)
                    SELECT ?,?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM CORE.WORKSPACE_SECURITY_EVENT WHERE ID=?)
                    """)) {
                statement.setString(1, payload.sha256());
                statement.setString(2, workspace.toString());
                statement.setString(3, turn.map(TurnId::toString).orElse(null));
                statement.setString(4, identity.root().toString());
                statement.setString(5, "SANDBOX_RESTORATION_UNCONFIRMED");
                statement.setString(6, payload.json());
                statement.setObject(7, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
                statement.setString(8, payload.sha256());
                statement.executeUpdate();
            }
            return null;
        });
        return true;
    }

    /**
     * 在读取项目或启动资源前校验安全锁；相同或交叠根重新登记也不能绕过。
     *
     * @param workspace 当前 Workspace
     * @param root 冻结的规范执行根
     */
    public void requireUnlocked(WorkspaceId workspace, Path root) {
        execute(connection -> {
            requireUnlocked(connection, workspace, root);
            return null;
        });
    }

    /**
     * 在事务外项目准备前拒绝安全锁，并记录准备期间可信 Worker 的恢复失败。
     *
     * @param workspace 权威 Workspace
     * @param request 已解析的 Turn 请求
     * @param environments 原有环境准备服务
     * @return 原服务生成的冻结请求
     */
    public TurnStartRequest prepare(
            WorkspaceId workspace, TurnStartRequest request, CodingEnvironmentService environments) {
        requireUnlocked(workspace, request.executionRoot());
        try {
            return environments.prepare(request);
        } catch (RuntimeException failure) {
            quarantine(workspace, Optional.empty(), request.executionRoot(), failure);
            throw failure;
        }
    }

    static void requireUnlocked(Connection connection, WorkspaceId workspace, Path root) throws SQLException {
        Path checked = root.toAbsolutePath().normalize();
        lockWorkspace(connection, workspace);
        try (var statement = connection.prepareStatement(
                        "SELECT WORKSPACE_ID,EXECUTION_ROOT FROM CORE.WORKSPACE_SECURITY_EVENT");
                var rows = statement.executeQuery()) {
            while (rows.next()) {
                Path locked = Path.of(rows.getString(2));
                if (rows.getString(1).equals(workspace.toString())
                        || checked.startsWith(locked)
                        || locked.startsWith(checked)) {
                    throw new SecurityException("WORKSPACE_SECURITY_LOCKED: 原生权限恢复未确认；必须核验同对象恢复凭据后才能再次执行。");
                }
            }
        }
    }

    private static void lockWorkspace(Connection connection, WorkspaceId workspace) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT ID FROM CORE.WORKSPACE WHERE ID=? FOR UPDATE")) {
            statement.setString(1, workspace.toString());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SecurityException("Workspace 不存在");
                }
            }
        }
    }

    /**
     * 返回平台内部完整恢复审计；调用方对外投影前必须校验 Workspace 权限并隐藏私有路径。
     *
     * @return 不可变安全事件；当前版本仅保留锁定事件，不伪造已恢复状态
     */
    public List<Event> events() {
        return execute(connection -> {
            var events = new ArrayList<Event>();
            try (var statement = connection.prepareStatement(
                            "SELECT ID,CODE,EVIDENCE_JSON,CREATED_AT FROM CORE.WORKSPACE_SECURITY_EVENT ORDER BY CREATED_AT,ID");
                    var rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(new Event(
                            rows.getString(1),
                            rows.getString(2),
                            json.decode(json.parse(rows.getString(3)), Evidence.class),
                            rows.getObject(4, OffsetDateTime.class).toInstant()));
                }
            }
            return List.copyOf(events);
        });
    }

    /**
     * 私有恢复证据；路径只从可信原生异常读取。
     *
     * @param workspace 事件所属 Workspace
     * @param turn 可空的已创建 Turn
     * @param root 冻结执行根
     * @param directories 保存基线及恢复尝试记录的私有目录
     */
    public record Evidence(WorkspaceId workspace, Optional<TurnId> turn, Path root, List<Path> directories) {
        /** 复制不可变目录集合。 */
        public Evidence {
            directories = List.copyOf(directories);
        }
    }

    /**
     * 平台内部安全审计记录。
     *
     * @param id 内容摘要标识
     * @param code 稳定失败类别
     * @param evidence 原始恢复凭据关联
     * @param occurredAt 事件时间
     */
    public record Event(String id, String code, Evidence evidence, Instant occurredAt) {}

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Workspace 安全审计事务失败", failure);
        }
    }
}
