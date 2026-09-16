package com.javaclaw.server.persistence;

import java.time.Clock;
import java.util.Optional;

import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Catalog;
import com.javaclaw.builtin.contracts.CodingSystemContracts.Registry;
import com.javaclaw.builtin.contracts.CodingSystemContracts.RegistryUpdate;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.system.SystemCommandCatalog;

/** Workspace 登记与 Turn 系统入口冻结服务；发现不持有数据库事务，配置不授予任何执行能力。 */
public final class CodingSystemService {
    private final H2Transactions transactions;
    private final CodingSystemRepository repository;
    private final SystemCommandCatalog discovery;
    private final CanonicalJson json;

    /**
     * 创建系统程序服务。
     *
     * @param database 已初始化数据库
     * @param json 规范协议编码
     * @param clock 持久记录时钟
     */
    public CodingSystemService(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(database);
        repository = new CodingSystemRepository(json, clock);
        discovery = new SystemCommandCatalog(database.dataRoot());
        this.json = json;
    }

    /**
     * 读取 Workspace 登记；未设置时版本为零。
     *
     * @param workspace 已授权 Workspace
     * @return 当前不可变登记
     */
    public Registry registry(WorkspaceId workspace) {
        return execute(connection -> repository.read(connection, workspace));
    }

    /**
     * 幂等替换登记，不运行程序且不修改权限策略。
     *
     * @param workspace 已授权 Workspace
     * @param identity 幂等身份及当前配置版本
     * @param request 完整新登记
     * @return 新配置版本
     */
    public Registry update(WorkspaceId workspace, CommandIdentity identity, RegistryUpdate request) {
        return execute(connection -> repository.update(connection, workspace, identity, request));
    }

    /**
     * 读取实际存在性和文件摘要；失败入口保留稳定原因。
     *
     * @param workspace 已授权 Workspace
     * @return 当前系统和登记程序目录，不代表调用者权限
     */
    public Catalog catalog(WorkspaceId workspace) {
        return discovery.discover(registry(workspace));
    }

    /**
     * 为新 Turn 准备目录；子任务、继续和自动化只继承已冻结事实。
     *
     * @param request 创建参数，调用者已经完成幂等恢复
     * @return 带有不可替换选择的创建参数
     */
    public TurnStartRequest prepare(TurnStartRequest request) {
        if (request.systemEnvironment().isPresent()) {
            return request;
        }
        Optional<TurnId> inherited = request.continuedFrom()
                .or(() -> execute(
                        connection -> new ChildTurnReservationRepository(json).parent(connection, request.threadId())));
        if (inherited.isPresent()) {
            return request.withSystemEnvironment(
                    new CodingSystemSelection(frozen(inherited.orElseThrow()), Optional.empty()));
        }
        WorkspaceId workspace = execute(connection -> new ThreadRepository()
                .find(connection, request.threadId())
                .orElseThrow(() -> PersistenceException.invalidRequest("Thread 不存在"))
                .workspaceId());
        Catalog catalog = catalog(workspace);
        return request.withSystemEnvironment(
                new CodingSystemSelection(catalog, Optional.of(catalog.registryRevision())));
    }

    /**
     * 读取历史 Turn 原目录；缺少历史快照时返回不可执行目录，绝不补入当前配置。
     *
     * @param turn 权威 Turn
     * @return 已校验摘要的目录
     */
    public Catalog frozen(TurnId turn) {
        return execute(connection -> repository.frozen(connection, turn));
    }

    /**
     * 为自动化保存一次发现结果，已有快照原样保留。
     *
     * @param snapshot 原工具目录捕获 ID
     * @param workspace 所属 Workspace
     */
    public void freezeExecution(TurnId snapshot, WorkspaceId workspace) {
        if (execute(connection -> repository.execution(connection, snapshot, workspace))
                .isPresent()) {
            return;
        }
        Catalog catalog = catalog(workspace);
        execute(connection -> {
            repository.saveExecution(
                    connection,
                    snapshot,
                    workspace,
                    new CodingSystemSelection(catalog, Optional.of(catalog.registryRevision())));
            return null;
        });
    }

    /**
     * 子执行复制父目录，不重新读取系统程序或当前登记。
     *
     * @param snapshot 子目录捕获 ID
     * @param workspace 子 Workspace
     * @param parent 父 Turn
     */
    public void freezeChild(TurnId snapshot, WorkspaceId workspace, TurnId parent) {
        execute(connection -> {
            repository.requireOwner(connection, parent, workspace);
            repository.saveExecution(
                    connection,
                    snapshot,
                    workspace,
                    new CodingSystemSelection(repository.frozen(connection, parent), Optional.empty()));
            return null;
        });
    }

    /**
     * 恢复自动化或子执行目录；缺失旧 sidecar 不获得新增系统能力。
     *
     * @param snapshot 原目录捕获 ID
     * @param workspace 当前 Workspace
     * @return 保持原始内容的继承选择
     */
    public CodingSystemSelection execution(TurnId snapshot, WorkspaceId workspace) {
        return new CodingSystemSelection(
                execute(connection -> repository
                        .execution(connection, snapshot, workspace)
                        .orElseGet(CodingSystemRepository::unavailable)),
                Optional.empty());
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("系统程序登记事务失败", failure);
        }
    }
}
