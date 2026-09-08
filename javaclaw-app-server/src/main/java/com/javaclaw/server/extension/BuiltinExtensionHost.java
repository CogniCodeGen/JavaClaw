package com.javaclaw.server.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionResolver;
import com.javaclaw.api.ToolCallRequest;
import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.api.UnattendedExecutionScope;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionBundle;
import com.javaclaw.extension.spi.ExtensionContributions;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionHandler;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionJobRegistrar;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionSchema;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.extension.spi.ScheduleTargetCatalogPort;
import com.javaclaw.extension.spi.ScheduledCommand;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ViewSchemaWireCodec;
import com.javaclaw.server.extension.contract.ExtensionHost;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.PermissionProfileService;

/** 显式启动可信内置 Bundle，并在每次调用前执行实时状态与权限收窄。 */
public final class BuiltinExtensionHost implements ExtensionHost, ScheduleTargetCatalogPort {
    private final Map<ExtensionId, RegisteredExtension> extensions;
    private final CoreCommandService core;
    private final PermissionProfileService profiles;
    private final BuiltinExtensionRuntimePorts ports;
    private final Optional<com.javaclaw.server.coding.CodingPlatform> coding;
    private final CanonicalJson json = new CanonicalJson();
    private final ViewSchemaWireCodec viewSchemas = new ViewSchemaWireCodec(json);

    private BuiltinExtensionHost(
            Map<ExtensionId, RegisteredExtension> extensions,
            CoreCommandService core,
            PermissionProfileService profiles,
            BuiltinExtensionRuntimePorts ports,
            Optional<com.javaclaw.server.coding.CodingPlatform> coding) {
        this.extensions = Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
        this.core = core;
        this.profiles = profiles;
        this.ports = ports;
        this.coding = coding;
    }

    /**
     * 启动并校验内置扩展；任一扩展失败会关闭本批次已启动 Bundle。
     *
     * @param bundles 显式内置 Bundle 清单
     * @param core Core 查询端口
     * @param profiles 权限配置端口
     * @param ports 扩展运行端口
     * @return 已启动 Host
     * @throws Exception Bundle 启动或关闭失败
     */
    public static BuiltinExtensionHost start(
            List<ExtensionBundle> bundles,
            CoreCommandService core,
            PermissionProfileService profiles,
            BuiltinExtensionRuntimePorts ports)
            throws Exception {
        return start(bundles, core, profiles, ports, Optional.empty());
    }

    /**
     * 启动内置扩展并绑定可选 Coding 平台；只有应用组合根提供真实执行端口。
     *
     * @param bundles 内置 Bundle
     * @param core Core 查询
     * @param profiles 权限服务
     * @param ports 通用运行端口
     * @param coding 可信 Coding 平台，兼容测试或最小宿主可为空
     * @return 已启动 Host，接管 Coding 平台关闭职责
     * @throws Exception 扩展注册失败
     */
    public static BuiltinExtensionHost start(
            List<ExtensionBundle> bundles,
            CoreCommandService core,
            PermissionProfileService profiles,
            BuiltinExtensionRuntimePorts ports,
            Optional<com.javaclaw.server.coding.CodingPlatform> coding)
            throws Exception {
        Objects.requireNonNull(bundles, "bundles");
        LinkedHashMap<ExtensionId, RegisteredExtension> registered = new LinkedHashMap<>();
        try {
            for (ExtensionBundle bundle : bundles) {
                ExtensionId id =
                        Objects.requireNonNull(bundle, "bundle").descriptor().id();
                if (registered.containsKey(id)) {
                    throw new IllegalArgumentException("duplicate extension: " + id.value());
                }
                RegisteredExtension extension = BuiltinExtensionRegistry.register(bundle, ports);
                registered.put(extension.descriptor().id(), extension);
            }
            BuiltinExtensionRegistry.validateGlobalTools(registered.values());
            for (RegisteredExtension extension : registered.values()) {
                ports.catalog().installBuiltIn(extension.descriptor());
                ports.managedStore().inTransaction(extension.descriptor().id(), transaction -> null);
            }
            return new BuiltinExtensionHost(
                    registered, core, profiles, ports, Objects.requireNonNull(coding, "coding"));
        } catch (Exception failure) {
            BuiltinExtensionRegistry.closeReverse(new ArrayList<>(registered.values()), failure);
            throw failure;
        }
    }

    /**
     * 返回实时扩展目录。
     *
     * @return 稳定安装顺序的摘要
     */
    public List<ExtensionRpcContracts.Summary> list() {
        return extensions.values().stream().map(this::summary).toList();
    }

    /**
     * 在平台回调端口完成绑定后，逐 Workspace 恢复全部启用 Bundle 的后台状态。
     *
     * @throws Exception 任一 Bundle 恢复失败；调用方必须终止启动
     */
    public void restore() throws Exception {
        for (RegisteredExtension extension : extensions.values()) {
            if (ports.catalog().state(extension.descriptor().id()) != ExtensionState.ENABLED) {
                continue;
            }
            for (Workspace workspace : core.listWorkspaces()) {
                PermissionProfile caller = profiles.resolve(PermissionProfileService.STANDARD_PROFILE_ID, 1, workspace);
                PermissionProfile effective = PermissionResolver.intersect(
                        List.of(caller, extension.descriptor().requirements().permissionCeiling()));
                extension.bundle().restore(context(extension, workspace, effective, new CancellationSource()));
            }
        }
    }

    /**
     * 把全部内置 Bundle 的 Job executor 注册到平台 Supervisor。
     *
     * @param registrar 平台注册端口
     */
    public void registerJobExecutors(ExtensionJobRegistrar registrar) {
        ExtensionJobRegistrar checked = Objects.requireNonNull(registrar, "registrar");
        for (RegisteredExtension extension : extensions.values()) {
            extension
                    .jobExecutors()
                    .forEach(registration ->
                            checked.register(extension.descriptor().id(), registration));
        }
        coding.ifPresent(platform -> platform.registerJobs(checked));
    }

    /**
     * 返回当前启用扩展贡献的工具；调用方据此创建 Turn 冻结快照。
     *
     * @return 按全局工具名排序的不可变描述
     */
    public List<ToolDescriptor> tools() {
        return extensions.values().stream()
                .filter(extension ->
                        ports.catalog().state(extension.descriptor().id()) == ExtensionState.ENABLED)
                .flatMap(extension -> extension.tools().values().stream())
                .map(ExtensionContributions.Tool::descriptor)
                .sorted(Comparator.comparing(tool -> tool.identity().name()))
                .toList();
    }

    /**
     * 列出启用扩展公开的可调度 Definition 精确版本。
     *
     * @param workspaceId Workspace
     * @return 按扩展、Definition 与 revision 排序的权威目录
     * @throws Exception 扩展目录读取失败
     */
    @Override
    public List<DefinitionOption> definitions(com.javaclaw.api.WorkspaceId workspaceId) throws Exception {
        Objects.requireNonNull(workspaceId, "workspaceId");
        List<DefinitionOption> result = new ArrayList<>();
        for (RegisteredExtension extension : extensions.values()) {
            if (!enabled(extension)) {
                continue;
            }
            ExtensionExecutionContext ownerContext = context(extension, workspaceId);
            for (ExtensionContributions.SchedulableDefinition definition : extension.definitionCatalog()) {
                for (DefinitionEntry entry : definition.provider().list(ownerContext)) {
                    result.add(new DefinitionOption(
                            extension.descriptor().id().value(),
                            entry.definitionId(),
                            entry.revision(),
                            definition.displayName() + " · " + entry.displayName()));
                }
            }
        }
        return result.stream()
                .sorted(Comparator.comparing(DefinitionOption::extensionId)
                        .thenComparing(DefinitionOption::definitionId)
                        .thenComparingLong(DefinitionOption::revision))
                .toList();
    }

    /**
     * 列出启用扩展显式公开给管理中心的 SchedulableAction。
     *
     * @param workspaceId Workspace
     * @return 稳定排序的权威目录
     */
    @Override
    public List<ActionOption> actions(com.javaclaw.api.WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        List<ActionOption> result = new ArrayList<>();
        for (RegisteredExtension extension : extensions.values()) {
            if (!enabled(extension)) {
                continue;
            }
            for (ExtensionContributions.SchedulableAction action : extension.actionCatalog()) {
                result.add(new ActionOption(
                        extension.descriptor().id().value(),
                        action.commandOperation(),
                        action.displayName(),
                        action.fields(),
                        action.expectedRevision()));
            }
        }
        return result.stream()
                .sorted(Comparator.comparing(ActionOption::extensionId).thenComparing(ActionOption::operation))
                .toList();
    }

    /**
     * 执行已经存在于 Turn 冻结目录中的扩展工具。
     *
     * <p>进入 Handler 前会重新读取 Extension 状态并校验 producer、revision、Schema 与实时权限上限。
     *
     * @param request 工具调用
     * @param frozenDescriptor 冻结描述
     * @param callerPermissions 最新调用方权限
     * @param cancellation Turn 取消信号
     * @return 扩展规范结果
     * @throws Exception Handler 失败
     */
    public ExtensionResponse executeTool(
            ToolCallRequest request,
            ToolDescriptor frozenDescriptor,
            PermissionProfile callerPermissions,
            CancellationToken cancellation)
            throws Exception {
        return executeToolWithFacts(request, frozenDescriptor, callerPermissions, cancellation)
                .response();
    }

    @Override
    public com.javaclaw.server.extension.contract.GovernedExtensionResponse executeToolWithFacts(
            ToolCallRequest request,
            ToolDescriptor frozenDescriptor,
            PermissionProfile callerPermissions,
            CancellationToken cancellation)
            throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(frozenDescriptor, "frozenDescriptor");
        Objects.requireNonNull(callerPermissions, "callerPermissions");
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        RegisteredExtension extension =
                require(new ExtensionId(frozenDescriptor.identity().producerId()));
        requireEnabled(extension);
        ExtensionContributions.Tool tool =
                extension.tools().get(frozenDescriptor.identity().name());
        if (tool == null || !tool.descriptor().equals(frozenDescriptor)) {
            throw new IllegalArgumentException("extension tool differs from frozen catalog");
        }
        var turn =
                core.findTurn(request.turnId()).orElseThrow(() -> new IllegalArgumentException("Turn does not exist"));
        Workspace workspace = core.workspaceForThread(turn.threadId());
        ExtensionRequest extensionRequest = new ExtensionRequest(
                workspace.id(),
                Optional.of(turn.threadId()),
                Optional.of(turn.id()),
                tool.contributionId(),
                request.arguments(),
                Optional.of(request.idempotencyKey()),
                0,
                core.unattendedExecutionScope(turn.id()));
        return invokeTool(extension, workspace, callerPermissions, cancellation, request, extensionRequest, tool);
    }

    private com.javaclaw.server.extension.contract.GovernedExtensionResponse invokeTool(
            RegisteredExtension extension,
            Workspace workspace,
            PermissionProfile caller,
            CancellationToken cancellation,
            ToolCallRequest request,
            ExtensionRequest extensionRequest,
            ExtensionContributions.Tool tool)
            throws Exception {
        PermissionProfile effective = PermissionResolver.intersect(
                List.of(caller, extension.descriptor().requirements().permissionCeiling()));
        if (isCoding(extension)) {
            try (var binding = coding.orElseThrow(() -> new SecurityException("Coding 平台尚未装配"))
                    .bindTool(request, caller, cancellation)) {
                var response = tool.handler()
                        .handle(extensionRequest, context(extension, workspace, effective, cancellation, binding));
                return binding.result(response);
            }
        }
        return new com.javaclaw.server.extension.contract.GovernedExtensionResponse(
                tool.handler().handle(extensionRequest, context(extension, workspace, effective, cancellation)),
                List.of());
    }

    private static boolean isCoding(RegisteredExtension extension) {
        return extension.descriptor().id().value().equals(com.javaclaw.builtin.contracts.CodingContracts.EXTENSION_ID);
    }

    /**
     * 执行无副作用 query。
     *
     * @param call wire 调用
     * @return 规范结果
     * @throws Exception Handler 失败
     */
    public ExtensionResponse query(ExtensionRpcContracts.CallPayload call) throws Exception {
        ServerScheduleDefinitionBindings.requirePublicOperation(call);
        return invoke(call, Optional.empty(), 0, ContributionKind.QUERY, Optional.empty());
    }

    /**
     * 执行幂等 command。
     *
     * @param call wire 调用
     * @param idempotencyKey 幂等键
     * @param expectedRevision 目标 revision
     * @return 规范结果
     * @throws Exception Handler 失败
     */
    public ExtensionResponse command(
            ExtensionRpcContracts.CallPayload call, String idempotencyKey, long expectedRevision) throws Exception {
        ServerScheduleDefinitionBindings.requirePublicOperation(call);
        return invoke(call, Optional.of(idempotencyKey), expectedRevision, ContributionKind.COMMAND, Optional.empty());
    }

    // 内部入口仅供具有组合根绑定身份的适配器调用；禁止把 payload 中的 owner 当作调用权限。
    ExtensionResponse scheduleBinding(ExtensionRpcContracts.CallPayload call, Optional<String> key) throws Exception {
        return invoke(
                call, key, 0, key.isPresent() ? ContributionKind.COMMAND : ContributionKind.QUERY, Optional.empty());
    }

    /**
     * 执行扩展显式声明为可调度的命令，并在调用前实时检查启用状态。
     *
     * @param call 固定 Workspace、operation 与 payload
     * @param command 冻结参数、revision、Schema 与 Schedule 来源
     * @param cancellation 调度取消信号
     * @return 规范响应
     * @throws Exception 目标撤权、版本冲突或执行失败
     */
    public ExtensionResponse scheduledCommand(
            ExtensionRpcContracts.CallPayload call, ScheduledCommand command, CancellationToken cancellation)
            throws Exception {
        Objects.requireNonNull(cancellation, "cancellation").throwIfCancelled();
        ScheduledCommand checked = Objects.requireNonNull(command, "command");
        if (!call.workspaceId().equals(checked.workspaceId())
                || !call.extensionId().equals(checked.extensionId())
                || !call.operation().equals(checked.operation())
                || !call.payload().equals(checked.payload())) {
            throw new IllegalArgumentException("scheduled command routing identity changed");
        }
        RegisteredExtension extension = require(new ExtensionId(call.extensionId()));
        requireEnabled(extension);
        if (!extension.schedulableActions().contains(call.operation())) {
            throw new IllegalArgumentException("extension operation is not schedulable");
        }
        requireCurrentActionBinding(extension, checked);
        return invoke(
                call,
                Optional.of(checked.idempotencyKey()),
                checked.expectedRevision(),
                ContributionKind.COMMAND,
                checked.unattendedExecutionScope());
    }

    private void requireCurrentActionBinding(RegisteredExtension extension, ScheduledCommand command) {
        Optional<ExtensionContributions.SchedulableAction> visible = extension.actionCatalog().stream()
                .filter(action -> action.commandOperation().equals(command.operation()))
                .findFirst();
        if (visible.isEmpty()) {
            if (command.actionSchemaHash().isPresent()) {
                throw new IllegalArgumentException("internal scheduled command must not declare an Action Schema");
            }
            return;
        }
        ExtensionContributions.SchedulableAction action = visible.orElseThrow();
        ActionOption option = new ActionOption(
                extension.descriptor().id().value(),
                action.commandOperation(),
                action.displayName(),
                action.fields(),
                action.expectedRevision());
        if (option.expectedRevision() != command.expectedRevision()
                || !option.schemaHash()
                        .equals(command.actionSchemaHash()
                                .orElseThrow(() -> new IllegalArgumentException("SchedulableAction Schema 未冻结")))) {
            throw new IllegalArgumentException("SchedulableAction Schema 或 revision 已改变");
        }
        if (option.fields().isEmpty()) {
            if (!json.fieldNames(command.payload()).isEmpty()) {
                throw new IllegalArgumentException("无参数 SchedulableAction 收到了参数");
            }
            return;
        }
        json.requireFlatPrimitiveObjectValue(option.inputSchema(), command.payload());
    }

    /**
     * 读取扩展公开 schema。
     *
     * @param extensionId 扩展标识
     * @param schemaId schema 标识
     * @return schema
     */
    public ExtensionSchema schema(String extensionId, String schemaId) {
        RegisteredExtension extension = require(new ExtensionId(extensionId));
        requireEnabled(extension);
        return extension.schemas().stream()
                .filter(candidate -> candidate.schemaId().equals(schemaId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("extension schema does not exist"));
    }

    /**
     * 列出声明式页面。
     *
     * @param extensionId 可选扩展过滤
     * @return 页面文档
     */
    public List<ExtensionRpcContracts.ViewDocument> views(Optional<String> extensionId) {
        Optional<ExtensionId> filter = extensionId.map(ExtensionId::new);
        return extensions.values().stream()
                .filter(extension -> filter.isEmpty()
                        || filter.orElseThrow().equals(extension.descriptor().id()))
                .peek(this::requireEnabled)
                .flatMap(extension -> extension.views().stream()
                        .map(view -> new ExtensionRpcContracts.ViewDocument(
                                extension.descriptor().id().value(), view.viewId(), viewSchemas.encode(view))))
                .toList();
    }

    private ExtensionResponse invoke(
            ExtensionRpcContracts.CallPayload call,
            Optional<String> idempotencyKey,
            long expectedRevision,
            ContributionKind kind,
            Optional<UnattendedExecutionScope> unattendedExecutionScope)
            throws Exception {
        Objects.requireNonNull(call, "call");
        RegisteredExtension extension = require(new ExtensionId(call.extensionId()));
        requireEnabled(extension);
        validateOwnership(call);
        ExtensionHandler handler = extension.handler(kind, call.operation());
        ExtensionRequest request = new ExtensionRequest(
                call.workspaceId(),
                call.threadId(),
                call.turnId(),
                call.operation(),
                call.payload(),
                idempotencyKey,
                expectedRevision,
                unattendedExecutionScope);
        if (isCoding(extension)) {
            try (var binding = coding.orElseThrow(() -> new SecurityException("Coding 平台尚未装配"))
                    .bindManagement(request, kind, new CancellationSource())) {
                var response = handler.handle(request, context(extension, call.workspaceId(), binding));
                return binding.result(response).response();
            }
        }
        return handler.handle(request, context(extension, call.workspaceId()));
    }

    private ExtensionExecutionContext context(RegisteredExtension extension, com.javaclaw.api.WorkspaceId workspaceId) {
        return context(extension, workspaceId, com.javaclaw.extension.spi.WorkspaceExecutionPort.denied());
    }

    private ExtensionExecutionContext context(
            RegisteredExtension extension,
            com.javaclaw.api.WorkspaceId workspaceId,
            com.javaclaw.extension.spi.WorkspaceExecutionPort execution) {
        Workspace workspace = core.findWorkspace(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Workspace does not exist"));
        PermissionProfile caller = profiles.resolve(PermissionProfileService.STANDARD_PROFILE_ID, 1, workspace);
        PermissionProfile effective = PermissionResolver.intersect(
                List.of(caller, extension.descriptor().requirements().permissionCeiling()));
        return context(extension, workspace, effective, new CancellationSource(), execution);
    }

    private ExtensionExecutionContext context(
            RegisteredExtension extension,
            Workspace workspace,
            PermissionProfile effective,
            CancellationToken cancellation) {
        return context(
                extension,
                workspace,
                effective,
                cancellation,
                com.javaclaw.extension.spi.WorkspaceExecutionPort.denied());
    }

    private ExtensionExecutionContext context(
            RegisteredExtension extension,
            Workspace workspace,
            PermissionProfile effective,
            CancellationToken cancellation,
            com.javaclaw.extension.spi.WorkspaceExecutionPort execution) {
        return BuiltinExtensionContexts.create(ports, this, extension, workspace, effective, cancellation, execution);
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
            if (call.threadId().filter(id -> !id.equals(turn.threadId())).isPresent()) {
                throw new IllegalArgumentException("Turn does not belong to Thread");
            }
            if (!core.workspaceForThread(turn.threadId()).id().equals(call.workspaceId())) {
                throw new IllegalArgumentException("Turn does not belong to Workspace");
            }
        });
    }

    private ExtensionRpcContracts.Summary summary(RegisteredExtension extension) {
        ExtensionDescriptor descriptor = extension.descriptor();
        return new ExtensionRpcContracts.Summary(
                descriptor.id().value(),
                descriptor.displayName(),
                descriptor.version(),
                descriptor.revision(),
                ports.catalog().state(descriptor.id()).name(),
                descriptor.requirements().trust().name(),
                descriptor.contributionKinds().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()));
    }

    private void requireEnabled(RegisteredExtension extension) {
        ports.catalog()
                .requireEnabled(
                        extension.descriptor().id(), extension.descriptor().revision());
    }

    private boolean enabled(RegisteredExtension extension) {
        return ports.catalog().state(extension.descriptor().id()) == ExtensionState.ENABLED;
    }

    private RegisteredExtension require(ExtensionId id) {
        RegisteredExtension extension = extensions.get(id);
        if (extension == null) {
            throw new IllegalArgumentException("extension is not installed: " + id.value());
        }
        return extension;
    }

    /** 按逆序关闭全部 Bundle。 */
    @Override
    public void close() throws Exception {
        try {
            BuiltinExtensionRegistry.closeReverse(new ArrayList<>(extensions.values()), null);
        } finally {
            if (coding.isPresent()) {
                coding.orElseThrow().close();
            }
        }
    }
}
