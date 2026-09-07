package com.javaclaw.server.coding;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.TurnId;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionJobRegistrar;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.WorkspaceExecutionPort;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.runtime.TurnFailureException;
import com.javaclaw.runtime.TurnResourceFinalizer;
import com.javaclaw.server.extension.contract.GovernedExtensionResponse;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CodingCommandOutputRepository;
import com.javaclaw.server.persistence.CodingEnvironmentRepository;
import com.javaclaw.server.persistence.CodingOperationRepository;
import com.javaclaw.server.persistence.CodingTerminalRepository;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.turn.CodingExecutionAuthority;

/**
 * 可信 Coding 平台组合；扩展仅取得与当前已批准调用完全绑定的一次执行能力。
 *
 * <p>普通管理 RPC 只有环境、安装与证据查询路径，不能构造项目执行能力。文件与进程事实独立收集，扩展响应不能覆盖事实。
 */
public final class CodingPlatform implements TurnResourceFinalizer, AutoCloseable {
    private final Dependencies dependencies;
    private final CodingOperationRepository operations;
    private final CodingEnvironmentRepository environments;
    private final CodingFileTools files;
    private final CodingProcessManager processes;
    private final CodingTerminalManager terminals;
    private final CodingManagement management;
    private final CodingDependencyPreparer preparation;
    private final CodingCallScope calls;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建平台执行作用域并把遗留活动进程标为未知；不恢复外部副作用。
     *
     * @param dependencies 组合根显式依赖
     * @throws Exception 原生端口或应用数据目录不可用
     */
    public CodingPlatform(Dependencies dependencies) throws Exception {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        calls = new CodingCallScope(dependencies.authority());
        operations = new CodingOperationRepository(dependencies.database(), dependencies.json(), dependencies.clock());
        environments =
                new CodingEnvironmentRepository(dependencies.database(), dependencies.json(), dependencies.clock());
        CodingTerminalRepository terminalRecords = new CodingTerminalRepository(
                dependencies.database(), dependencies.attachments(), dependencies.json(), dependencies.clock());
        terminalRecords.recoverInterrupted();
        CodingExecutionLocks locks = new CodingExecutionLocks();
        files = new CodingFileTools(
                dependencies.json(),
                new CodingPatchExecutor(dependencies.json(), operations, dependencies.attachments(), locks));
        ManagedCommandResolver resolver = new ManagedCommandResolver(
                dependencies.toolchains(),
                dependencies.database().dataRoot(),
                dependencies.clock(),
                dependencies.core().codingEnvironments()::requireCompatible);
        processes = new CodingProcessManager(
                new CodingProcessManager.Services(
                        dependencies.json(),
                        operations,
                        dependencies.attachments(),
                        new CodingCommandOutputRepository(dependencies.database(), dependencies.json())),
                resolver,
                locks,
                dependencies.authority(),
                dependencies.sandbox());
        terminals = new CodingTerminalManager(
                new CodingTerminalManager.Services(dependencies.json(), terminalRecords, operations),
                resolver,
                locks,
                dependencies.authority(),
                dependencies.sandbox());
        preparation = new CodingDependencyPreparer(dependencies, operations, processes, resolver);
        management = new CodingManagement(dependencies, environments, operations, terminals);
    }

    /**
     * 捕获原始已授权调用，Bundle 自身的存储上限不能替换调用方的执行权限。
     *
     * @param request Turn 平台生成的工具身份与参数
     * @param permission 已完成审批与交集的有效权限
     * @param cancellation 当前 Turn 取消信号
     * @return 仅当前 Handler 作用域可调用的能力
     */
    public Binding bindTool(ToolCallRequest request, PermissionProfile permission, CancellationToken cancellation) {
        requireOpen();
        AgentTurn turn = dependencies
                .core()
                .findTurn(request.turnId())
                .orElseThrow(() -> new SecurityException("Coding Turn 不存在"));
        PermissionProfile current = dependencies.authority().current(turn.id(), permission);
        var workspace = dependencies.core().workspaceForThread(turn.threadId());
        String id = "coding-"
                + dependencies
                        .json()
                        .encode(new InvocationId(turn.id(), request.callId()))
                        .sha256();
        try {
            CodingDataBoundary.requireOutside(
                    turn.executionRoot(),
                    current,
                    dependencies.database().dataRoot(),
                    dependencies.authority().managedWorktree(turn.id()));
        } catch (SecurityException denied) {
            return new Binding(() -> new GovernedExtensionResponse(
                    new ExtensionResponse(
                            dependencies
                                    .json()
                                    .encode(CodingFailures.result(id, denied).value()),
                            0),
                    List.of(),
                    false));
        }
        CodingInvocation invocation = new CodingInvocation(
                id, request, turn, workspace.id(), current, cancellation, environments.frozen(turn.id()));
        return new Binding(() -> calls.run(invocation, this::execute));
    }

    /**
     * 捕获管理请求；调用种类独立于请求声明的 Thread 或 Turn ID。
     *
     * @param request 已验证扩展管理信封
     * @param kind QUERY 或 COMMAND，不接受 TOOL
     * @param cancellation 管理请求取消信号
     * @return 不拥有项目执行能力的单次端口
     */
    public Binding bindManagement(ExtensionRequest request, ContributionKind kind, CancellationToken cancellation) {
        requireOpen();
        return new Binding(
                () -> new GovernedExtensionResponse(management.invoke(request, kind, cancellation), List.of()));
    }

    /**
     * 注册不执行项目脚本的安装作业。
     *
     * @param registrar 平台 Job Supervisor
     */
    public void registerJobs(ExtensionJobRegistrar registrar) {
        dependencies.toolchains().registerJobs(registrar);
    }

    private GovernedExtensionResponse execute(CodingInvocation invocation) throws Exception {
        requireOpen();
        invocation.cancellation().throwIfCancelled();
        CanonicalPayload identity = dependencies
                .json()
                .encode(new InvocationIdentity(
                        invocation.request(),
                        dependencies.json().encode(invocation.environment()).sha256(),
                        invocation.permission(),
                        invocation.turn().toolCatalogDigest()));
        var intent = new CodingOperationRepository.Intent(
                invocation.id(),
                invocation.turn().id(),
                invocation.workspaceId(),
                invocation.request().callId(),
                invocation.request().tool().name(),
                invocation.turn().executionRoot(),
                identity);
        var previous = operations.prepare(intent);
        if (previous.result().isPresent()) {
            return new GovernedExtensionResponse(
                    new ExtensionResponse(previous.result().orElseThrow(), 0), previous.facts(), previous.success());
        }
        if (!previous.state().equals("PREPARED")) {
            throw new TurnFailureException("UNKNOWN_OUTCOME", "Coding 操作已经开始但结果无法确认，禁止自动重放");
        }
        CodingToolResult result;
        try {
            result = dispatch(invocation);
        } catch (Exception failure) {
            if (dependencies
                    .authority()
                    .reportIsolationFailure(invocation.turn().id(), failure)) {
                if (operations
                        .find(invocation.workspaceId(), invocation.id())
                        .orElseThrow()
                        .state()
                        .equals("STARTED")) {
                    operations.unknown(invocation.id());
                }
                var locked =
                        new TurnFailureException("WORKSPACE_SECURITY_LOCKED", "原生权限恢复未确认，Workspace 已锁定；恢复凭据和执行意图已保留。");
                locked.addSuppressed(failure);
                throw locked;
            }
            result = preflightFailure(invocation, failure);
        }
        try {
            CanonicalPayload payload = dependencies.json().encode(result.value());
            operations.finish(invocation.id(), payload, result.facts(), result.success());
            return new GovernedExtensionResponse(new ExtensionResponse(payload, 0), result.facts(), result.success());
        } catch (Exception failure) {
            throw unknown(invocation, failure);
        }
    }

    private CodingToolResult preflightFailure(CodingInvocation invocation, Exception failure) {
        try {
            var observed =
                    operations.find(invocation.workspaceId(), invocation.id()).orElseThrow();
            if (observed.state().equals("PREPARED")) {
                return CodingFailures.result(invocation.id(), failure);
            }
        } catch (Exception persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
        throw unknown(invocation, failure);
    }

    private TurnFailureException unknown(CodingInvocation invocation, Exception failure) {
        try {
            operations.unknown(invocation.id());
        } catch (Exception evidenceFailure) {
            failure.addSuppressed(evidenceFailure);
        }
        var unknown = new TurnFailureException("UNKNOWN_OUTCOME", "Coding 操作或事实提交结果未确认；备份和意图已保留，禁止自动重放");
        unknown.addSuppressed(failure);
        return unknown;
    }

    private CodingToolResult dispatch(CodingInvocation invocation) throws Exception {
        String operation = invocation.request().tool().name();
        if (operation.startsWith("file_")) {
            return files.execute(operation, invocation);
        }
        if (operation.startsWith("terminal_")) {
            return terminals.execute(operation, invocation);
        }
        if (operation.equals("command_run")) {
            return processes.run(invocation);
        }
        if (operation.equals("dependencies_prepare")) {
            return preparation.prepare(invocation);
        }
        throw new SecurityException("Coding 未声明此操作");
    }

    @Override
    public void finish(TurnId turnId) throws Exception {
        closeResources(List.of(
                () -> calls.finish(turnId), () -> preparation.finish(turnId),
                () -> terminals.finish(turnId), () -> processes.finish(turnId)));
    }

    /** 关闭全部租约与进程；每个资源均尝试收敛，失败保留并上报。 */
    @Override
    public void close() throws Exception {
        if (closed.compareAndSet(false, true)) {
            closeResources(List.of(calls, preparation, terminals, processes, dependencies.toolchains()));
        }
    }

    private static void closeResources(List<AutoCloseable> resources) throws Exception {
        Exception failure = null;
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception current) {
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Coding 平台已关闭");
        }
    }

    /**
     * 组合根依赖；各组件均不可空，平台接管 toolchains 的关闭职责。
     *
     * @param database data-v6
     * @param core 权威 Core 查询
     * @param authority 实时执行授权
     * @param attachments 内容寻址附件
     * @param toolchains 托管工具链
     * @param sandbox 原生沙箱
     * @param json 规范 JSON
     * @param clock 平台时钟
     */
    public record Dependencies(
            H2Database database,
            CoreCommandService core,
            CodingExecutionAuthority authority,
            AttachmentService attachments,
            CodingToolchainPort toolchains,
            PlatformSandboxExecutor sandbox,
            CanonicalJson json,
            Clock clock) {}

    /** 单 Handler 能力，关闭后或第二次调用必定拒绝；事实与原响应由平台封存。 */
    public static final class Binding implements WorkspaceExecutionPort, AutoCloseable {
        private final Operation operation;
        private final AtomicBoolean available = new AtomicBoolean(true);
        private GovernedExtensionResponse response;

        private Binding(Operation operation) {
            this.operation = operation;
        }

        @Override
        public ExtensionResponse invoke() throws Exception {
            if (!available.compareAndSet(true, false)) {
                throw new SecurityException("Coding 执行能力已经使用或离开调用作用域");
            }
            response = operation.execute();
            return response.response();
        }

        /**
         * 核验 Bundle 确实返回本次平台响应，不能伪造或替换执行事实。
         *
         * @param returned Handler 返回值
         * @return 平台权威执行结果
         */
        public GovernedExtensionResponse result(ExtensionResponse returned) {
            if (response == null || !response.response().equals(returned)) {
                throw new SecurityException("Coding Handler 未返回本次平台回执");
            }
            return response;
        }

        @Override
        public void close() {
            available.set(false);
        }
    }

    @FunctionalInterface
    private interface Operation {
        GovernedExtensionResponse execute() throws Exception;
    }

    private record InvocationId(TurnId turnId, String callId) {}

    private record InvocationIdentity(
            ToolCallRequest request,
            String environmentDigest,
            PermissionProfile permission,
            String toolCatalogDigest) {}
}
