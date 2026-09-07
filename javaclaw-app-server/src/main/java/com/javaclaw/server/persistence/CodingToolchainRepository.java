package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingContracts;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.InstallAccepted;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.InstallationState;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.InstalledToolchain;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainArtifact;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainRef;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJob;
import com.javaclaw.extension.spi.ExtensionJobSubmission;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.WriteCommand;

/** 安装引用、持久 Job、首个 Outbox 和幂等响应的原子提交边界。 */
public final class CodingToolchainRepository {
    /** 固定安装 Job 类型，不接受请求覆盖执行器。 */
    public static final String JOB_TYPE = "toolchain-install";

    private final H2Transactions transactions;
    private final CanonicalJson json;
    private final Clock clock;
    private final ExtensionJobRepository jobs = new ExtensionJobRepository();
    private final ExtensionJobOutboxRepository outbox = new ExtensionJobOutboxRepository();
    private final IdempotentCommandStore commands = new IdempotentCommandStore();

    /**
     * 创建安装事务访问器。
     *
     * @param database 应用数据库
     * @param json 规范 JSON
     * @param clock 提交时间来源
     */
    public CodingToolchainRepository(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(database);
        this.json = java.util.Objects.requireNonNull(json, "json");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /**
     * 创建安装或复用同一 Workspace 的活动、已完成安装；同一幂等键始终恢复原响应。
     *
     * @param request 已授权管理请求
     * @param artifact 从可信目录解析的完整制品
     * @return 已提交安装 Job 身份
     */
    public InstallAccepted submit(ExtensionRequest request, ToolchainArtifact artifact) {
        if (request.expectedRevision() != 0) {
            throw PersistenceException.invalidRequest("安装工具链的 expected revision 必须为 0");
        }
        var payload = json.encode(new InstallIdentity(request.workspaceId(), request.payload()));
        var identity = CommandIdentity.from(
                "coding/toolchain/install",
                new WriteCommand(request.idempotencyKey().orElseThrow(), 0, payload),
                json);
        synchronized (CommandLocks.forKey(identity.idempotencyKey())) {
            return execute(connection -> {
                Optional<CanonicalPayload> recovered = commands.recover(connection, identity);
                if (recovered.isPresent()) {
                    return json.decode(recovered.orElseThrow(), InstallAccepted.class);
                }
                lockWorkspace(connection, request.workspaceId());
                Optional<String> existing = reusableJob(connection, request.workspaceId(), artifact.reference());
                String jobId = existing.orElseGet(() -> create(connection, request.workspaceId(), artifact));
                var result = new InstallAccepted(jobId, artifact.reference().artifactSha256());
                commands.record(connection, identity, json.encode(result), clock.instant());
                return result;
            });
        }
    }

    /**
     * 查询安装状态；取消或失败的 Job 不再显示为进行中。
     *
     * @param workspace Workspace
     * @return 精确版本的安装记录
     */
    public List<InstalledToolchain> list(WorkspaceId workspace) {
        return execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT T.*, J.STATE AS JOB_STATE FROM CORE.CODING_TOOLCHAIN T
                    LEFT JOIN CORE.EXTENSION_JOB J ON J.ID = T.JOB_ID
                    WHERE T.WORKSPACE_ID = ? ORDER BY T.TOOLCHAIN_KIND, T.ARTIFACT_DIGEST
                    """)) {
                statement.setString(1, workspace.toString());
                List<InstalledToolchain> result = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(map(rows));
                    }
                }
                return List.copyOf(result);
            }
        });
    }

    /**
     * 记录已原子发布并校验的制品；旧 Job 无法覆盖重试安装。
     *
     * @param job 安装工作单元所属 Job
     * @param ready 是否有完整文件证据
     * @param errorCode 失败时的稳定错误码，成功时为空
     */
    public void finish(ExtensionJob job, boolean ready, Optional<String> errorCode) {
        execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE CORE.CODING_TOOLCHAIN SET STATE = ?, ERROR_CODE = ?, UPDATED_AT = ?
                    WHERE WORKSPACE_ID = ? AND JOB_ID = ?
                    """)) {
                statement.setString(1, ready ? "READY" : "FAILED");
                statement.setString(2, errorCode.orElse(null));
                statement.setObject(3, clock.instant().atOffset(ZoneOffset.UTC));
                statement.setString(4, job.workspaceId().toString());
                statement.setString(5, job.id());
                if (statement.executeUpdate() != 1) {
                    throw PersistenceException.revisionConflict("工具链安装 Job 已被替换");
                }
            }
            return null;
        });
    }

    /**
     * 将文件校验失败的 READY 引用标为损坏，允许新的幂等命令创建修复 Job。
     *
     * @param workspace 所属 Workspace
     * @param reference 已校验失败的精确版本
     */
    public void damaged(WorkspaceId workspace, ToolchainRef reference) {
        execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE CORE.CODING_TOOLCHAIN SET STATE = 'FAILED', ERROR_CODE = 'TOOLCHAIN_CORRUPTED'
                    WHERE WORKSPACE_ID = ? AND TOOLCHAIN_KIND = ? AND ARTIFACT_DIGEST = ? AND STATE = 'READY'
                    """)) {
                statement.setString(1, workspace.toString());
                statement.setString(2, reference.kind().name());
                statement.setString(3, reference.artifactSha256());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private String create(Connection connection, WorkspaceId workspace, ToolchainArtifact artifact) {
        try {
            var submission = new ExtensionJobSubmission(
                    new ExtensionId(CodingContracts.EXTENSION_ID),
                    workspace,
                    JOB_TYPE,
                    artifact.reference().artifactSha256(),
                    1,
                    json.encode(artifact),
                    json.parse("{\"installed\":false}"));
            ExtensionJob job = jobs.insert(connection, UUID.randomUUID().toString(), submission, clock.instant());
            saveInstallation(connection, job, artifact);
            outbox.enqueue(
                    connection,
                    "extension-job:" + job.id() + ":revision:" + job.revision(),
                    json.encode(new InputJobRpcContracts.JobReadPayload(job.id())),
                    clock.instant());
            return job.id();
        } catch (SQLException failure) {
            throw new PersistenceException("创建工具链安装 Job 失败", failure);
        }
    }

    private void saveInstallation(Connection connection, ExtensionJob job, ToolchainArtifact artifact)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                MERGE INTO CORE.CODING_TOOLCHAIN (ARTIFACT_DIGEST, TOOLCHAIN_KIND, WORKSPACE_ID,
                    ARTIFACT_JSON, INSTALLATION_ID, JOB_ID, STATE, ERROR_CODE, CREATED_AT, UPDATED_AT)
                KEY (WORKSPACE_ID, TOOLCHAIN_KIND, ARTIFACT_DIGEST)
                VALUES (?, ?, ?, ?, ?, ?, 'INSTALLING', NULL, ?, ?)
                """)) {
            statement.setString(1, artifact.reference().artifactSha256());
            statement.setString(2, artifact.reference().kind().name());
            statement.setString(3, job.workspaceId().toString());
            statement.setString(4, json.encode(artifact).json());
            statement.setString(
                    5,
                    artifact.reference().kind().name() + "-"
                            + artifact.reference().artifactSha256());
            statement.setString(6, job.id());
            statement.setObject(7, clock.instant().atOffset(ZoneOffset.UTC));
            statement.setObject(8, clock.instant().atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private Optional<String> reusableJob(Connection connection, WorkspaceId workspace, ToolchainRef reference)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT JOB_ID, STATE FROM CORE.CODING_TOOLCHAIN
                WHERE WORKSPACE_ID = ? AND TOOLCHAIN_KIND = ? AND ARTIFACT_DIGEST = ?
                """)) {
            statement.setString(1, workspace.toString());
            statement.setString(2, reference.kind().name());
            statement.setString(3, reference.artifactSha256());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                String id = row.getString("JOB_ID");
                ExtensionJob job = jobs.find(connection, id).orElseThrow();
                boolean reusable =
                        row.getString("STATE").equals("READY") || !job.state().terminal();
                return reusable ? Optional.of(id) : Optional.empty();
            }
        }
    }

    private InstalledToolchain map(ResultSet row) throws SQLException {
        ToolchainArtifact artifact =
                json.decode(new CanonicalPayload(row.getString("ARTIFACT_JSON")), ToolchainArtifact.class);
        InstallationState state = InstallationState.valueOf(row.getString("STATE"));
        String error = row.getString("ERROR_CODE");
        String jobState = row.getString("JOB_STATE");
        if (state == InstallationState.INSTALLING
                && jobState != null
                && ExecutionState.valueOf(jobState).terminal()) {
            state = InstallationState.FAILED;
            error = "TOOLCHAIN_JOB_" + jobState;
        }
        Optional<java.time.Instant> installed = state == InstallationState.READY
                ? Optional.of(row.getObject("UPDATED_AT", OffsetDateTime.class).toInstant())
                : Optional.empty();
        return new InstalledToolchain(
                artifact.reference(), row.getString("INSTALLATION_ID"), state, installed, Optional.ofNullable(error));
    }

    private static void lockWorkspace(Connection connection, WorkspaceId workspace) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT STATE FROM CORE.WORKSPACE WHERE ID = ? FOR UPDATE")) {
            statement.setString(1, workspace.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || row.getString("STATE").equals("ARCHIVED")) {
                    throw PersistenceException.invalidRequest("Workspace 不存在或已归档");
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
            throw new PersistenceException("工具链安装事务失败", failure);
        }
    }

    private record InstallIdentity(WorkspaceId workspaceId, CanonicalPayload payload) {}
}
