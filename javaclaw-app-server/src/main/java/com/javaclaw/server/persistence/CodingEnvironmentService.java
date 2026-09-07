package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingEnvironmentContracts.ToolchainKind;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.toolchain.CodingEnvironmentSelection;
import com.javaclaw.server.toolchain.CodingToolchainCatalog;
import com.javaclaw.server.toolchain.ProjectToolchainSelector;

/** 将事务外原生读取与事务内精确冻结分开；恢复、子任务和活动自动化不重新选择当前版本。 */
public final class CodingEnvironmentService {
    private final H2Transactions transactions;
    private final CodingEnvironmentRepository environments;
    private final CodingSelectionRepository selections;
    private final CanonicalJson json;
    private final ProjectToolchainSelector selector;
    private final WorkspaceSecurityRepository security;

    /**
     * 创建使用原生受限读取器的环境准备服务。
     *
     * @param database 应用数据库
     * @param json 规范 JSON
     * @param clock 记录时间
     */
    public CodingEnvironmentService(H2Database database, CanonicalJson json, Clock clock) {
        this(database, json, clock, new ProjectToolchainSelector(CodingToolchainCatalog.bundled()));
    }

    /**
     * 注入读取边界用于离线事务验证。
     *
     * @param database 应用数据库
     * @param json 规范 JSON
     * @param clock 记录时间
     * @param selector 无数据库事务的受限声明选择器
     */
    public CodingEnvironmentService(
            H2Database database, CanonicalJson json, Clock clock, ProjectToolchainSelector selector) {
        transactions = new H2Transactions(database);
        environments = new CodingEnvironmentRepository(database, json, clock);
        selections = new CodingSelectionRepository(json, clock);
        this.json = json;
        this.selector = selector;
        security = new WorkspaceSecurityRepository(database, json, clock);
    }

    /**
     * 为尚未提交的 Turn 准备选择；调用者必须先完成幂等恢复。
     *
     * @param request 已验证的内部创建请求
     * @return 可在单事务内落库的请求
     */
    public TurnStartRequest prepare(TurnStartRequest request) {
        if (request.codingEnvironment().isPresent()) {
            return request;
        }
        Optional<TurnId> parent =
                execute(connection -> new ChildTurnReservationRepository(json).parent(connection, request.threadId()));
        if (parent.isPresent()) {
            return request.withCodingEnvironment(frozen(parent.orElseThrow()));
        }
        WorkspaceId workspace = execute(connection -> new ThreadRepository()
                .find(connection, request.threadId())
                .orElseThrow(() -> PersistenceException.invalidRequest("Thread 不存在"))
                .workspaceId());
        var environment = environments.read(workspace);
        boolean coding = request.toolCatalog().tools().stream()
                .anyMatch(tool -> List.of("command_run", "terminal_open", "dependencies_prepare")
                        .contains(tool.identity().name()));
        var selection = coding
                ? selector.select(request.executionRoot(), request.toolCatalog().permissionCeiling(), environment)
                : new CodingEnvironmentSelection(environment, Map.of(), Map.of(), false);
        return request.withCodingEnvironment(selection);
    }

    /**
     * 为新的自动化 Execution 准备并保存一次项目选择；Sidecar ID 使用原工具目录的捕获 Turn ID。
     *
     * @param snapshotId 原始工具目录捕获 ID
     * @param workspace 权威 Workspace
     * @param root 执行根
     * @param permission 冻结文件权限
     */
    public void freezeExecution(TurnId snapshotId, WorkspaceId workspace, Path root, PermissionProfile permission) {
        if (execute(connection -> selections.find(connection, snapshotId, workspace))
                .isPresent()) {
            return;
        }
        security.requireUnlocked(workspace, root);
        CodingEnvironmentSelection selection;
        try {
            selection = selector.select(root, permission, environments.read(workspace));
        } catch (RuntimeException failure) {
            security.quarantine(workspace, Optional.empty(), root, failure);
            throw failure;
        }
        execute(connection -> {
            selections.save(connection, snapshotId, workspace, selection);
            return null;
        });
    }

    /**
     * 子快照继承父 Turn 精确环境；缺少历史证据时保存不可执行选择，不重建当前默认值。
     *
     * @param snapshotId 子工具目录捕获 ID
     * @param workspace 所属 Workspace
     * @param parent 父 Turn
     */
    public void freezeChild(TurnId snapshotId, WorkspaceId workspace, TurnId parent) {
        execute(connection -> {
            selections.save(connection, snapshotId, workspace, selections.frozen(connection, parent));
            return null;
        });
    }

    /**
     * 恢复已冻结自动化选择；旧快照缺少 Sidecar 时不能获得新增 Coding 能力。
     *
     * @param snapshotId 原始工具目录捕获 ID
     * @param workspace 当前执行 Workspace
     * @return 原精确选择或明确不可执行的历史选择
     */
    public CodingEnvironmentSelection execution(TurnId snapshotId, WorkspaceId workspace) {
        return execute(connection ->
                selections.find(connection, snapshotId, workspace).orElseGet(CodingSelectionRepository::unavailable));
    }

    /**
     * 读取 Turn 选择并校验摘要。
     *
     * @param turn 权威 Turn ID
     * @return 不可变选择，恢复不会重新读取项目
     */
    public CodingEnvironmentSelection frozen(TurnId turn) {
        return execute(connection -> selections.frozen(connection, turn));
    }

    /**
     * 在启动实际命令前检查所用种类的声明兼容性。
     *
     * @param turn 当前 Turn
     * @param kinds 固定命令解析出的工具种类
     */
    public void requireCompatible(TurnId turn, List<ToolchainKind> kinds) {
        frozen(turn).requireCompatible(kinds);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("Coding 环境准备事务失败", failure);
        }
    }
}
