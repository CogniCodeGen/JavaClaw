package com.javaclaw.server.extension.thirdparty;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.NetworkBroker;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.SandboxExecutor;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ExtensionAccessDeniedException;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.server.extension.contract.ExtensionHost;
import com.javaclaw.server.mcp.SignedBundleMcpLaunch;
import com.javaclaw.server.mcp.SignedBundleMcpSource;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ThirdPartyExtensionRecord;
import com.javaclaw.server.persistence.ThirdPartyExtensionRepository;

/** 第三方 Extension 的进程外调用 Host 与管理入口。 */
public final class ThirdPartyExtensionHost implements ExtensionHost, SignedBundleMcpSource {
    private final ThirdPartyExtensionRepository repository;
    private final ExtensionCatalogRepository catalog;
    private final ThirdPartyBundleRegistry registry;
    private final ThirdPartyBundleManager manager;
    private final ThirdPartyTrashManager trash;
    private final ExtensionTrustKeyService trust;
    private final ThirdPartyWorkerClient workers;
    private final CoreCommandService core;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();

    ThirdPartyExtensionHost(Dependencies dependencies) {
        Objects.requireNonNull(dependencies, "dependencies");
        repository = dependencies.repository();
        catalog = dependencies.catalog();
        registry = dependencies.registry();
        manager = dependencies.manager();
        trash = dependencies.trash();
        trust = dependencies.trust();
        workers = dependencies.workers();
        core = dependencies.core();
        clock = dependencies.clock();
    }

    /**
     * 从 data-v6 管理员信任目录恢复并重新验签全部第三方 Bundle。
     *
     * @param database 已初始化数据库
     * @param json 共享 JSON codec
     * @param clock 平台时钟
     * @param execution 进程隔离与宿主网络执行端口
     * @param core Core 查询服务
     * @param reservedTools 内置扩展已经占用的工具名称
     * @param attachments Core Attachment 服务
     * @return 已恢复 Host
     */
    public static ThirdPartyExtensionHost start(
            H2Database database,
            CanonicalJson json,
            Clock clock,
            ExecutionPorts execution,
            CoreCommandService core,
            List<ToolDescriptor> reservedTools,
            AttachmentService attachments) {
        ThirdPartyBundleDirectories directories = new ThirdPartyBundleDirectories(database.dataRoot(), clock);
        return ThirdPartyExtensionFactory.start(new ThirdPartyExtensionFactory.Bootstrap(
                database, json, clock, execution, core, reservedTools, directories, attachments));
    }

    /**
     * 第三方扩展的外部执行边界。
     *
     * @param sandbox 无 shell、默认断网的进程 Sandbox
     * @param network 唯一允许 Worker 间接联网的 Host Broker
     */
    public record ExecutionPorts(SandboxExecutor sandbox, NetworkBroker network) {
        /** 校验所有端口已显式装配。 */
        public ExecutionPorts {
            Objects.requireNonNull(sandbox, "sandbox");
            Objects.requireNonNull(network, "network");
        }
    }

    /**
     * 验签并解压到 staging，返回必须由用户确认的精确权限摘要。
     *
     * @param attachment Core Attachment ID 与摘要
     * @return staging 审阅结果
     */
    public BundleRpcContracts.StageResult stage(BundleRpcContracts.AttachmentPointer attachment) {
        requireOpen();
        return manager.stage(attachment);
    }

    /**
     * 安装用户已经按 manifest 摘要确认权限的 staging Bundle。
     *
     * @param stagingId staging 标识
     * @param approvedManifestDigest 用户确认的 manifest 摘要
     * @param expectedRevision 新安装必须为 0
     * @return 安装后摘要，初始状态为 INSTALLED
     */
    public BundleRpcContracts.Bundle install(BundleRpcContracts.CommitPayload request, long expectedRevision) {
        requireOpen();
        return manager.install(request, expectedRevision);
    }

    /**
     * 健康检查通过后原子切换到新 Bundle revision。
     *
     * @param request 已确认 staging
     * @param expectedRevision 当前 Bundle revision
     * @return 新 Bundle 详情
     */
    public BundleRpcContracts.Bundle upgrade(BundleRpcContracts.CommitPayload request, long expectedRevision) {
        requireOpen();
        return manager.upgrade(request, expectedRevision);
    }

    /**
     * 健康检查通过后启用第三方 Bundle。
     *
     * @param extensionId 扩展标识
     * @param expectedRevision Bundle revision
     * @return ENABLED 摘要
     */
    public BundleRpcContracts.Bundle enable(String extensionId, long expectedRevision) {
        requireOpen();
        return manager.enable(extensionId, expectedRevision);
    }

    /**
     * 实时阻止后续新调用。
     *
     * @param extensionId 扩展标识
     * @param expectedRevision Bundle revision
     * @return DISABLED 摘要
     */
    public BundleRpcContracts.Bundle disable(String extensionId, long expectedRevision) {
        requireOpen();
        return manager.disable(extensionId, expectedRevision);
    }

    /**
     * 探测 Bundle Worker 健康状态并更新失败与退避。
     *
     * @param extensionId 扩展标识
     * @param expectedRevision Bundle revision
     * @return 最新 Bundle 详情
     */
    public BundleRpcContracts.Bundle probe(String extensionId, long expectedRevision) {
        requireOpen();
        return manager.probe(extensionId, expectedRevision);
    }

    /** @return 第三方 Bundle 管理详情 */
    public List<BundleRpcContracts.Bundle> listBundles() {
        requireOpen();
        return manager.list();
    }

    /** @param extensionId 扩展标识 @return Bundle 管理详情 */
    public BundleRpcContracts.Bundle readBundle(String extensionId) {
        requireOpen();
        return manager.read(extensionId);
    }

    /** @param extensionId 扩展标识 @param expectedRevision 当前 revision @return Trash 条目 */
    public BundleRpcContracts.TrashEntry uninstall(String extensionId, long expectedRevision) {
        requireOpen();
        return trash.uninstall(extensionId, expectedRevision);
    }

    /** @return Bundle Trash 历史 */
    public List<BundleRpcContracts.TrashEntry> listTrash() {
        requireOpen();
        return trash.list();
    }

    /** @param trashId Trash 标识 @return Trash 条目 */
    public BundleRpcContracts.TrashEntry readTrash(String trashId) {
        requireOpen();
        return trash.read(trashId);
    }

    /** @param trashId Trash 标识 @param expectedRevision 被卸载 revision @return 恢复后的 Bundle */
    public BundleRpcContracts.Bundle restoreTrash(String trashId, long expectedRevision) {
        requireOpen();
        return trash.restore(trashId, expectedRevision);
    }

    /** @param trashId Trash 标识 @param expectedRevision 被卸载 revision @return PURGED tombstone */
    public BundleRpcContracts.TrashEntry purgeTrash(String trashId, long expectedRevision) {
        requireOpen();
        return trash.purge(trashId, expectedRevision);
    }

    /** @return Trust Key 元数据 */
    public List<BundleRpcContracts.TrustKey> listTrustKeys() {
        requireOpen();
        return trust.list();
    }

    /** @param keyId 密钥标识 @return Trust Key 元数据 */
    public BundleRpcContracts.TrustKey readTrustKey(String keyId) {
        requireOpen();
        return trust.read(keyId);
    }

    /** @param request Attachment 导入请求 @param expectedRevision 必须为 0 @return Trust Key */
    public BundleRpcContracts.TrustKey importTrustKey(
            BundleRpcContracts.TrustKeyImportPayload request, long expectedRevision) {
        requireOpen();
        return trust.importKey(request, expectedRevision);
    }

    /** @param keyId 密钥标识 @param expectedRevision 当前 revision @return REVOKED Trust Key */
    public BundleRpcContracts.TrustKey revokeTrustKey(String keyId, long expectedRevision) {
        requireOpen();
        return trust.revoke(keyId, expectedRevision);
    }

    /**
     * 判断标识是否属于当前第三方安装目录，包括禁用和隔离状态。
     *
     * @param extensionId 扩展标识
     * @return 已安装时为 true
     */
    public boolean installed(String extensionId) {
        requireOpen();
        return repository.find(new ExtensionId(extensionId)).isPresent();
    }

    /**
     * 读取已验签且当前可调用的唯一 MCP stdio 贡献。
     *
     * <p>每次调用都复用 {@link #requireCallable(ExtensionId)} 的实时目录、revision、隔离与退避检查；禁用 Bundle 或撤销签名者会立即阻止新的 MCP 交换。
     *
     * @param bundleId Bundle 标识
     * @return 精确启动快照
     */
    @Override
    public SignedBundleMcpLaunch require(String bundleId) {
        InstalledThirdPartyBundle bundle = requireCallable(new ExtensionId(bundleId));
        List<ThirdPartyBundleManifest.Contribution> contributions = bundle.manifest().contributions().stream()
                .filter(value -> value.kind() == com.javaclaw.extension.spi.ContributionKind.MCP)
                .toList();
        if (contributions.size() != 1) {
            throw new ExtensionAccessDeniedException("signed Bundle must contain exactly one MCP contribution");
        }
        ThirdPartyBundleManifest.EntryPoint entryPoint = bundle.manifest().entryPoint();
        java.nio.file.Path executable =
                bundle.root().resolve(entryPoint.executable()).normalize();
        if (!executable.startsWith(bundle.root())) {
            throw new ExtensionAccessDeniedException("signed MCP executable escapes the Bundle root");
        }
        java.util.ArrayList<String> argv = new java.util.ArrayList<>();
        argv.add(executable.toString());
        argv.addAll(entryPoint.arguments());
        ThirdPartyBundleManifest.Contribution contribution = contributions.getFirst();
        return new SignedBundleMcpLaunch(
                bundle.descriptor().id().value(),
                bundle.descriptor().revision(),
                contribution.contributionId(),
                contribution.descriptor().orElseThrow(),
                bundle.root(),
                argv,
                bundle.manifest().permissions().maxRunTime(),
                bundle.manifest().permissions().resources());
    }

    @Override
    public List<ExtensionRpcContracts.Summary> list() {
        requireOpen();
        return repository.list().stream()
                .map(ThirdPartyBundleManager::genericSummary)
                .toList();
    }

    @Override
    public List<ToolDescriptor> tools() {
        requireOpen();
        return registry.sorted().stream()
                .filter(this::available)
                .flatMap(bundle -> bundle.tools().values().stream())
                .map(InstalledThirdPartyBundle.ToolContribution::descriptor)
                .sorted(Comparator.comparing(tool -> tool.identity().name()))
                .toList();
    }

    @Override
    public ExtensionResponse executeTool(
            ToolCallRequest request,
            ToolDescriptor frozenDescriptor,
            PermissionProfile callerPermissions,
            CancellationToken cancellation)
            throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(frozenDescriptor, "frozenDescriptor");
        InstalledThirdPartyBundle bundle =
                requireCallable(new ExtensionId(frozenDescriptor.identity().producerId()));
        InstalledThirdPartyBundle.ToolContribution tool =
                bundle.tools().get(frozenDescriptor.identity().name());
        if (tool == null
                || !tool.descriptor().equals(frozenDescriptor)
                || !request.tool().equals(frozenDescriptor.identity())) {
            throw new IllegalArgumentException("third-party tool differs from frozen catalog");
        }
        var turn =
                core.findTurn(request.turnId()).orElseThrow(() -> new IllegalArgumentException("Turn does not exist"));
        Workspace workspace = core.workspaceForThread(turn.threadId());
        return invokeTracked(
                bundle,
                new ThirdPartyInvocation(
                        ThirdPartyWorkerClient.InvocationKind.TOOL,
                        tool.contributionId(),
                        workspace,
                        Optional.of(turn.threadId()),
                        Optional.of(turn.id()),
                        request.arguments(),
                        Optional.of(request.idempotencyKey()),
                        0,
                        Optional.of(callerPermissions),
                        cancellation));
    }

    @Override
    public ExtensionResponse query(ExtensionRpcContracts.CallPayload call) throws Exception {
        return invokeCall(call, Optional.empty(), 0, ThirdPartyWorkerClient.InvocationKind.QUERY);
    }

    @Override
    public ExtensionResponse command(
            ExtensionRpcContracts.CallPayload call, String idempotencyKey, long expectedRevision) throws Exception {
        return invokeCall(
                call,
                Optional.of(Objects.requireNonNull(idempotencyKey, "idempotencyKey")),
                expectedRevision,
                ThirdPartyWorkerClient.InvocationKind.COMMAND);
    }

    @Override
    public ExtensionSchema schema(String extensionId, String schemaId) {
        InstalledThirdPartyBundle bundle = requireCallable(new ExtensionId(extensionId));
        return bundle.schemas().stream()
                .filter(schema -> schema.schemaId().equals(schemaId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("extension schema does not exist"));
    }

    @Override
    public List<ExtensionRpcContracts.ViewDocument> views(Optional<String> extensionId) {
        requireOpen();
        if (extensionId.isPresent()) {
            return requireCallable(new ExtensionId(extensionId.orElseThrow())).views();
        }
        return registry.sorted().stream()
                .filter(this::available)
                .flatMap(bundle -> bundle.views().stream())
                .toList();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            registry.clear();
        }
    }

    private ExtensionResponse invokeCall(
            ExtensionRpcContracts.CallPayload call,
            Optional<String> idempotencyKey,
            long expectedRevision,
            ThirdPartyWorkerClient.InvocationKind kind)
            throws Exception {
        Objects.requireNonNull(call, "call");
        InstalledThirdPartyBundle bundle = requireCallable(new ExtensionId(call.extensionId()));
        requireOperation(bundle, kind, call.operation());
        validateOwnership(call);
        Workspace workspace = core.findWorkspace(call.workspaceId())
                .orElseThrow(() -> new IllegalArgumentException("Workspace does not exist"));
        return invokeTracked(
                bundle,
                new ThirdPartyInvocation(
                        kind,
                        call.operation(),
                        workspace,
                        call.threadId(),
                        call.turnId(),
                        call.payload(),
                        idempotencyKey,
                        expectedRevision,
                        Optional.empty(),
                        new com.javaclaw.api.CancellationSource()));
    }

    private ExtensionResponse invokeTracked(InstalledThirdPartyBundle bundle, ThirdPartyInvocation invocation)
            throws Exception {
        ThirdPartyExtensionRecord current = requireRecord(bundle.descriptor().id());
        try {
            ExtensionResponse response = workers.invoke(bundle, invocation);
            if (current.failureCount() > 0) {
                repository.clearFailures(bundle.descriptor().id());
            }
            return response;
        } catch (Exception failure) {
            manager.recordInvocationFailure(current, failure);
            throw failure;
        }
    }

    private InstalledThirdPartyBundle requireCallable(ExtensionId id) {
        requireOpen();
        ThirdPartyExtensionRecord record = requireRecord(id);
        catalog.requireEnabled(id, record.descriptor().revision());
        if (record.nextRetryAt()
                .filter(retry -> retry.isAfter(Instant.now(clock)))
                .isPresent()) {
            throw new ExtensionAccessDeniedException("extension is waiting for its retry window");
        }
        InstalledThirdPartyBundle bundle = registry.require(id);
        if (!bundle.descriptor().equals(record.descriptor())) {
            throw new ExtensionAccessDeniedException("extension runtime differs from catalog");
        }
        return bundle;
    }

    private ThirdPartyExtensionRecord requireRecord(ExtensionId id) {
        return repository.find(id).orElseThrow(() -> new ExtensionAccessDeniedException("extension is not installed"));
    }

    private boolean available(InstalledThirdPartyBundle bundle) {
        try {
            requireCallable(bundle.descriptor().id());
            return true;
        } catch (ExtensionAccessDeniedException | IllegalArgumentException unavailable) {
            return false;
        }
    }

    private static void requireOperation(
            InstalledThirdPartyBundle bundle, ThirdPartyWorkerClient.InvocationKind kind, String operation) {
        boolean present =
                switch (kind) {
                    case QUERY -> bundle.queryOperations().containsKey(operation);
                    case COMMAND -> bundle.commandOperations().containsKey(operation);
                    default -> false;
                };
        if (!present) {
            throw new IllegalArgumentException("extension operation does not exist");
        }
    }

    private void validateOwnership(ExtensionRpcContracts.CallPayload call) {
        call.threadId().ifPresent(threadId -> {
            var thread =
                    core.findThread(threadId).orElseThrow(() -> new IllegalArgumentException("Thread does not exist"));
            if (!thread.workspaceId().equals(call.workspaceId())) {
                throw new IllegalArgumentException("Thread does not belong to Workspace");
            }
        });
        call.turnId().ifPresent(turnId -> {
            var turn = core.findTurn(turnId).orElseThrow(() -> new IllegalArgumentException("Turn does not exist"));
            if (call.threadId().filter(id -> !id.equals(turn.threadId())).isPresent()
                    || !core.workspaceForThread(turn.threadId()).id().equals(call.workspaceId())) {
                throw new IllegalArgumentException("Turn does not belong to call context");
            }
        });
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("third-party extension host is closed");
        }
    }

    record Dependencies(
            ThirdPartyExtensionRepository repository,
            ExtensionCatalogRepository catalog,
            ThirdPartyBundleRegistry registry,
            ThirdPartyBundleManager manager,
            ThirdPartyTrashManager trash,
            ExtensionTrustKeyService trust,
            ThirdPartyWorkerClient workers,
            CoreCommandService core,
            Clock clock) {
        Dependencies {
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(manager, "manager");
            Objects.requireNonNull(trash, "trash");
            Objects.requireNonNull(trust, "trust");
            Objects.requireNonNull(workers, "workers");
            Objects.requireNonNull(core, "core");
            Objects.requireNonNull(clock, "clock");
        }
    }
}
