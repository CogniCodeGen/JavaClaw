package com.javaclaw.server;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.IsolatedServicePort;
import com.javaclaw.extension.spi.LoginStartupPort;
import com.javaclaw.model.ProviderCredentialResolver;
import com.javaclaw.model.ProviderEmbeddingAdapterFactory;
import com.javaclaw.model.ProviderModelDiscoveryAdapter;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.nativehost.credential.SystemMasterKeyProtector;
import com.javaclaw.nativehost.startup.UserLoginStartup;
import com.javaclaw.nativehost.tray.LauncherSupervisorProbe;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProtocolNegotiator;
import com.javaclaw.protocol.StableCapabilities;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.server.config.VaultProviderCredentialResolver;
import com.javaclaw.server.diagnostics.DiagnosticsService;
import com.javaclaw.server.extension.BuiltinIsolatedServices;
import com.javaclaw.server.extension.contract.ExtensionHost;
import com.javaclaw.server.extension.thirdparty.ThirdPartyExtensionHost;
import com.javaclaw.server.instructions.ProjectInstructionResolver;
import com.javaclaw.server.lifecycle.ApprovalExpirationCoordinator;
import com.javaclaw.server.lifecycle.ApprovalLifecycleCoordinator;
import com.javaclaw.server.lifecycle.InputLifecycleCoordinator;
import com.javaclaw.server.lifecycle.LauncherLifecycleService;
import com.javaclaw.server.lifecycle.LifecycleCoordinator;
import com.javaclaw.server.lifecycle.ScheduleLifecycleCoordinator;
import com.javaclaw.server.mcp.McpPlatformFactory;
import com.javaclaw.server.mcp.McpProductionPortsFactory;
import com.javaclaw.server.mcp.McpRuntimePorts;
import com.javaclaw.server.persistence.AgentRoleService;
import com.javaclaw.server.persistence.ApprovalService;
import com.javaclaw.server.persistence.AttachmentService;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.EmbeddingBindingService;
import com.javaclaw.server.persistence.ExecutionConfigurationService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.ExtensionJobInputCoordinator;
import com.javaclaw.server.persistence.ExtensionJobService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.InputRequestService;
import com.javaclaw.server.persistence.ManagedWorktreeService;
import com.javaclaw.server.persistence.PermissionProfileService;
import com.javaclaw.server.persistence.PromptOptimizationRepository;
import com.javaclaw.server.persistence.ProviderCredentialService;
import com.javaclaw.server.persistence.ProviderModelDiscoveryService;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.persistence.ProviderVerificationService;
import com.javaclaw.server.persistence.RolloutCommandService;
import com.javaclaw.server.rpc.AppServerSession;
import com.javaclaw.server.rpc.CoreRpcHandlers;
import com.javaclaw.server.rpc.CredentialRpcHandlers;
import com.javaclaw.server.rpc.ExtensionBundleRpcHandlers;
import com.javaclaw.server.rpc.ExtensionEventHub;
import com.javaclaw.server.rpc.ExtensionRpcHandlers;
import com.javaclaw.server.rpc.InputJobRpcHandlers;
import com.javaclaw.server.rpc.InstructionRpcHandlers;
import com.javaclaw.server.rpc.LauncherLifecycleRpcHandlers;
import com.javaclaw.server.rpc.PermissionPresetRpcHandlers;
import com.javaclaw.server.rpc.PermissionProfileRpcHandlers;
import com.javaclaw.server.rpc.PromptOptimizationRpcHandlers;
import com.javaclaw.server.rpc.PromptPreviewRpcHandlers;
import com.javaclaw.server.rpc.ProviderCredentialRpcHandlers;
import com.javaclaw.server.rpc.ProviderEmbeddingBindingRpcHandlers;
import com.javaclaw.server.rpc.ProviderModelDiscoveryRpcHandlers;
import com.javaclaw.server.rpc.ProviderVerificationRpcHandlers;
import com.javaclaw.server.rpc.RpcRouter;
import com.javaclaw.server.rpc.SecurityGrantRpcHandlers;
import com.javaclaw.server.rpc.ToolRpcHandlers;
import com.javaclaw.server.security.PermissionPresetCatalog;
import com.javaclaw.server.security.grant.PrivateNetworkGrantService;
import com.javaclaw.server.security.grant.SecurityGrantAuditService;
import com.javaclaw.server.security.grant.UnattendedToolGrantService;
import com.javaclaw.server.security.vault.SecretVaultService;
import com.javaclaw.server.turn.CoreSystemInstruction;
import com.javaclaw.server.turn.ExtensionToolPlatform;
import com.javaclaw.server.turn.PromptOptimizationInstruction;
import com.javaclaw.server.turn.PromptOptimizationService;
import com.javaclaw.server.turn.PromptPreviewService;
import com.javaclaw.server.turn.TurnDispatcher;

/** App Server 唯一组合根；所有实现依赖在进程启动阶段显式装配。 */
public final class AppServerBootstrap {
    private AppServerBootstrap() {}

    /**
     * 从 data-v6 Provider 配置建立真实模型注册表。
     *
     * <p>凭据只在模型调用边界从 Vault 解封；Vault 锁定或引用失效时会安全拒绝调用，不会回退环境变量。
     *
     * @param dataRoot 必须以 {@code data-v6} 结尾
     * @param clock 平台时钟
     * @return 可关闭组件
     */
    public static Components create(Path dataRoot, Clock clock) {
        Foundation foundation = foundation(
                dataRoot, clock, SystemMasterKeyProtector.create(dataRoot), UserLoginStartup.fromSystemProperties());
        return createConfigured(foundation, new VaultProviderCredentialResolver(foundation.vault()));
    }

    /**
     * 创建发行版本地健康检查专用的 fail-closed App Server。
     *
     * <p>此路径显式锁定 Vault，不构造也不访问 macOS Keychain、Windows DPAPI/Credential Manager 或 Linux Secret Service。传输层必须由
     * {@link AppServerOptions} 限定为 stdio。
     *
     * @param dataRoot 必须以 {@code data-v6} 结尾的临时根目录
     * @param clock 平台时钟
     * @return Vault 始终锁定的可关闭组件
     */
    static Components createHealthCheck(Path dataRoot, Clock clock) {
        Foundation foundation = foundation(dataRoot, clock, new LockedMasterKeyProtector(), required -> {});
        return createConfigured(foundation, new VaultProviderCredentialResolver(foundation.vault()));
    }

    /**
     * 从 H2 配置与显式 Vault 读取边界建立模型注册表。
     *
     * @param dataRoot 必须以 {@code data-v6} 结尾
     * @param clock 平台时钟
     * @param credentials Secret Vault 解析边界
     * @return 可关闭组件
     */
    public static Components create(Path dataRoot, Clock clock, ProviderCredentialResolver credentials) {
        Foundation foundation = foundation(
                dataRoot, clock, SystemMasterKeyProtector.create(dataRoot), UserLoginStartup.fromSystemProperties());
        return createConfigured(foundation, credentials);
    }

    private static Components createConfigured(Foundation foundation, ProviderCredentialResolver credentials) {
        return ConfiguredProviderBootstrap.create(foundation, credentials);
    }

    /**
     * 创建带真实 Thin Harness 调度链的 App Server。
     *
     * @param dataRoot 必须以 {@code data-v6} 结尾
     * @param clock 平台时钟
     * @param models 已配置模型路由；所有权转移给返回组件
     * @return 可关闭组件
     */
    public static Components create(Path dataRoot, Clock clock, ModelGateway models) {
        return create(dataRoot, clock, models, UserLoginStartup.fromSystemProperties());
    }

    /**
     * 创建可注入登录启动端口的真实 App Server；平台测试可使用无外部副作用实现。
     *
     * @param dataRoot 必须以 {@code data-v6} 结尾
     * @param clock 平台时钟
     * @param models 已配置模型路由；所有权转移给返回组件
     * @param loginStartup Schedule 登录启动项端口
     * @return 可关闭组件
     */
    public static Components create(Path dataRoot, Clock clock, ModelGateway models, LoginStartupPort loginStartup) {
        return create(dataRoot, clock, models, loginStartup, BuiltinIsolatedServices.browserUnavailable());
    }

    /**
     * 创建可注入隔离服务端口的真实 App Server；主要用于平台级假 Worker 测试。
     *
     * <p>如果 {@code isolatedServices} 实现 {@link AutoCloseable}，其所有权会转移给返回组件。
     *
     * @param dataRoot 必须以 {@code data-v6} 结尾
     * @param clock 平台时钟
     * @param models 已配置模型路由；所有权转移给返回组件
     * @param loginStartup Schedule 登录启动项端口
     * @param isolatedServices 受控进程外服务路由
     * @return 可关闭组件
     */
    public static Components create(
            Path dataRoot,
            Clock clock,
            ModelGateway models,
            LoginStartupPort loginStartup,
            IsolatedServicePort isolatedServices) {
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(loginStartup, "loginStartup");
        Objects.requireNonNull(isolatedServices, "isolatedServices");
        Foundation foundation = foundation(dataRoot, clock, new LockedMasterKeyProtector(), loginStartup);
        try (StartupCloseStack startup = new StartupCloseStack()) {
            ownFoundation(startup, foundation);
            ProviderCredentialResolver credentials = new VaultProviderCredentialResolver(foundation.vault());
            return createReal(
                    foundation,
                    new AppServerRuntimeBootstrap.RuntimeDependencies(
                            models,
                            EmbeddingPort.unavailable(),
                            new ProviderEmbeddingAdapterFactory(credentials)::create,
                            modelDiscovery(foundation, credentials),
                            isolatedServices,
                            productionMcpPorts(foundation)),
                    startup);
        }
    }

    /**
     * 创建可注入 MCP Broker/Sandbox 端口的真实 App Server。
     *
     * @param dataRoot 必须以 {@code data-v6} 结尾
     * @param clock 平台时钟
     * @param models 已配置模型路由；所有权转移给返回组件
     * @param loginStartup Schedule 登录启动项端口
     * @param isolatedServices 受控进程外服务路由
     * @param mcpPorts MCP 远端、交互、网络和 OAuth 边界
     * @return 可关闭组件
     */
    public static Components create(
            Path dataRoot,
            Clock clock,
            ModelGateway models,
            LoginStartupPort loginStartup,
            IsolatedServicePort isolatedServices,
            McpRuntimePorts mcpPorts) {
        Objects.requireNonNull(models, "models");
        Objects.requireNonNull(loginStartup, "loginStartup");
        Objects.requireNonNull(isolatedServices, "isolatedServices");
        Objects.requireNonNull(mcpPorts, "mcpPorts");
        Foundation foundation = foundation(dataRoot, clock, new LockedMasterKeyProtector(), loginStartup);
        try (StartupCloseStack startup = new StartupCloseStack()) {
            ownFoundation(startup, foundation);
            ProviderCredentialResolver credentials = new VaultProviderCredentialResolver(foundation.vault());
            return createReal(
                    foundation,
                    new AppServerRuntimeBootstrap.RuntimeDependencies(
                            models,
                            EmbeddingPort.unavailable(),
                            new ProviderEmbeddingAdapterFactory(credentials)::create,
                            modelDiscovery(foundation, credentials),
                            isolatedServices,
                            mcpPorts),
                    startup);
        }
    }

    static Components createReal(
            Foundation foundation,
            AppServerRuntimeBootstrap.RuntimeDependencies dependencies,
            StartupCloseStack startup) {
        return AppServerRuntimeBootstrap.create(foundation, dependencies, startup);
    }

    static ProviderModelDiscoveryService modelDiscovery(Foundation foundation, ProviderCredentialResolver credentials) {
        return new ProviderModelDiscoveryService(
                foundation.providers(), new ProviderModelDiscoveryAdapter(credentials, foundation.clock()));
    }

    private static Foundation foundation(
            Path dataRoot, Clock clock, MasterKeyProtector masterKeys, LoginStartupPort loginStartup) {
        return PlatformFoundationFactory.create(dataRoot, clock, masterKeys, loginStartup);
    }

    static void ownFoundation(StartupCloseStack startup, Foundation foundation) {
        startup.own(foundation.previews());
        startup.own(foundation.vault());
        startup.own(foundation.approvals());
        startup.own(foundation.approvalLifecycle());
        startup.own(foundation.approvalExpiration());
        startup.own(foundation.lifecycle());
        startup.own(foundation.inputLifecycle());
    }

    static Components components(Foundation foundation, RuntimeAssembly runtime) {
        RpcRouter.Builder routes = RpcRouter.builder();
        ExtensionEventHub events = new ExtensionEventHub(foundation.json());
        LauncherLifecycleService launcher = launcherLifecycle(foundation);
        registerCoreRoutes(routes, foundation, runtime, diagnostics(foundation, runtime, launcher), launcher);
        registerExtensionRoutes(routes, foundation, runtime, events);
        Set<String> stableCapabilities =
                runtime.management().mcpAvailable() ? StableCapabilities.withMcp() : StableCapabilities.all();
        ProtocolNegotiator negotiator = new ProtocolNegotiator(stableCapabilities, Set.of());
        return new Components(
                foundation.json(),
                routes.build(),
                negotiator,
                foundation.lifecycle(),
                events,
                foundation.streams(),
                foundation.previews(),
                runtime.resources());
    }

    private static DiagnosticsService diagnostics(
            Foundation foundation, RuntimeAssembly runtime, LauncherLifecycleService launcher) {
        return new DiagnosticsService(
                new DiagnosticsService.Sources(
                        new DiagnosticsService.CoreSources(
                                foundation.database(), foundation.core(), foundation.lifecycle()),
                        new DiagnosticsService.PlatformSources(
                                foundation.providers(),
                                foundation.vault(),
                                foundation.extensionJobs(),
                                runtime.management().mcp().service()),
                        new DiagnosticsService.RuntimeSources(
                                runtime.extensions()::list,
                                runtime.management().workers(),
                                runtime.management().scheduleLifecycle(),
                                launcher::readStatus)),
                foundation.json(),
                foundation.clock(),
                foundation.startedAt());
    }

    private static void registerCoreRoutes(
            RpcRouter.Builder routes,
            Foundation foundation,
            RuntimeAssembly runtime,
            DiagnosticsService diagnostics,
            LauncherLifecycleService launcher) {
        CoreRpcHandlers.PlatformServices platform = new CoreRpcHandlers.PlatformServices(
                foundation.core(),
                foundation.attachments(),
                foundation.providers(),
                foundation.worktrees(),
                diagnostics);
        CoreRpcHandlers.InteractionServices interactions = new CoreRpcHandlers.InteractionServices(
                runtime.dispatcher(),
                new RolloutCommandService(foundation.database(), foundation.json(), foundation.clock()),
                foundation.approvals());
        new CoreRpcHandlers(platform, foundation.json(), interactions, Optional.of(foundation.streams()))
                .register(routes);
        new PermissionProfileRpcHandlers(foundation.core(), foundation.permissionProfiles(), foundation.json())
                .register(routes);
        new PermissionPresetRpcHandlers(
                        foundation.core(),
                        foundation.permissionProfiles(),
                        new PermissionPresetCatalog(),
                        foundation.json())
                .register(routes);
        registerRoleRoutes(routes, foundation);
        new SecurityGrantRpcHandlers(
                        foundation.privateNetworkGrants(),
                        foundation.unattendedToolGrants(),
                        foundation.securityGrantAudit(),
                        foundation.json())
                .register(routes);
        new CredentialRpcHandlers(foundation.vault(), foundation.json()).register(routes);
        registerProviderRoutes(routes, foundation, runtime);
        new InputJobRpcHandlers(foundation.inputs(), foundation.extensionJobs(), foundation.json()).register(routes);
        new InstructionRpcHandlers(
                        foundation.core(), foundation.worktrees(), foundation.instructions(), foundation.json())
                .register(routes);
        new PromptPreviewRpcHandlers(
                        new PromptPreviewService(
                                new com.javaclaw.server.turn.TurnPlatformServices(
                                        foundation.core(),
                                        foundation.agentRoles(),
                                        foundation.executionConfigurations(),
                                        foundation.permissionProfiles(),
                                        foundation.instructions(),
                                        foundation.worktrees()),
                                runtime.tools(),
                                CoreSystemInstruction.load(),
                                foundation.json()),
                        foundation.json())
                .register(routes);
        registerPromptOptimization(routes, foundation, runtime.dispatcher());
        if (runtime.dispatcher() instanceof com.javaclaw.server.turn.HarnessTurnDispatcher dispatcher) {
            new com.javaclaw.server.rpc.CollaborationRpcHandlers(
                            collaboration(foundation, dispatcher), foundation.json())
                    .register(routes);
        }
        new LauncherLifecycleRpcHandlers(launcher, foundation.json()).register(routes);
    }

    private static void registerProviderRoutes(
            RpcRouter.Builder routes, Foundation foundation, RuntimeAssembly runtime) {
        new ProviderCredentialRpcHandlers(foundation.providerCredentials(), foundation.json()).register(routes);
        new ProviderEmbeddingBindingRpcHandlers(foundation.embeddingBinding(), foundation.json()).register(routes);
        new com.javaclaw.server.rpc.ProviderContextRpcHandlers(
                        new com.javaclaw.server.persistence.ProviderContextService(
                                foundation.database(), foundation.providers(), foundation.json(), foundation.clock()),
                        foundation.json())
                .register(routes);
        new ProviderModelDiscoveryRpcHandlers(runtime.management().providerModelDiscovery(), foundation.json())
                .register(routes);
        new ProviderVerificationRpcHandlers(runtime.management().providerVerification(), foundation.json())
                .register(routes);
    }

    private static void registerRoleRoutes(RpcRouter.Builder routes, Foundation foundation) {
        new com.javaclaw.server.rpc.AgentRoleRpcHandlers(foundation.agentRoles(), foundation.json()).register(routes);
        new com.javaclaw.server.rpc.ExecutionRpcHandlers(foundation.executionConfigurations(), foundation.json())
                .register(routes);
        new com.javaclaw.server.rpc.AgentRoleFileRpcHandlers(
                        new com.javaclaw.server.persistence.AgentRoleFileService(
                                foundation.database(),
                                foundation.agentRoles(),
                                foundation.providers(),
                                foundation.json(),
                                foundation.clock()),
                        foundation.json())
                .register(routes);
    }

    static com.javaclaw.server.turn.AgentCollaborationService collaboration(
            Foundation foundation, com.javaclaw.server.turn.HarnessTurnDispatcher dispatcher) {
        return new com.javaclaw.server.turn.AgentCollaborationService(
                new com.javaclaw.server.turn.TurnPlatformServices(
                        foundation.core(),
                        foundation.agentRoles(),
                        foundation.executionConfigurations(),
                        foundation.permissionProfiles(),
                        foundation.instructions(),
                        foundation.worktrees()),
                foundation.core().childTurns(),
                dispatcher,
                foundation.json(),
                foundation.clock());
    }

    private static LauncherLifecycleService launcherLifecycle(Foundation foundation) {
        return new LauncherLifecycleService(
                foundation.database(),
                foundation.lifecycle(),
                LauncherSupervisorProbe.currentUser(),
                foundation.json(),
                foundation.clock());
    }

    private static void registerPromptOptimization(
            RpcRouter.Builder routes, Foundation foundation, TurnDispatcher turns) {
        PromptOptimizationService service = new PromptOptimizationService(
                foundation.core(),
                foundation.agentRoles(),
                new PromptOptimizationRepository(foundation.database(), foundation.json(), foundation.clock()),
                turns,
                PromptOptimizationInstruction.load(),
                foundation.json(),
                foundation.clock());
        service.resumeQueued();
        new PromptOptimizationRpcHandlers(service, foundation.json()).register(routes);
    }

    private static void registerExtensionRoutes(
            RpcRouter.Builder routes, Foundation foundation, RuntimeAssembly runtime, ExtensionEventHub events) {
        AppServerExtensionBootstrap.registerManagement(
                routes, runtime.management().mcp(), foundation.extensionCatalog(), foundation.json());
        new ExtensionRpcHandlers(runtime.extensions(), foundation.json(), events).register(routes);
        new ExtensionBundleRpcHandlers(runtime.thirdParty(), foundation.json()).register(routes);
        new ToolRpcHandlers(
                        foundation.core(),
                        foundation.agentRoles(),
                        foundation.permissionProfiles(),
                        runtime.tools(),
                        foundation.json())
                .register(routes);
    }

    static McpRuntimePorts productionMcpPorts(Foundation foundation) {
        return McpProductionPortsFactory.create(
                foundation.vault(), foundation.privateNetworkGrants(), foundation.json(), foundation.clock());
    }

    /**
     * 不可变的会话共享组件。
     *
     * @param json 共享 JSON codec
     * @param router 不可变路由表
     * @param negotiator 能力协商器
     * @param lifecycle 客户端、Turn 与后台任务生命周期
     * @param events Extension 状态失效通知
     * @param streams 持久聊天流与提交唤醒
     * @param previews 连接隔离的文档快照服务
     * @param resources Turn 执行器与 Provider 客户端
     */
    public record Components(
            CanonicalJson json,
            RpcRouter router,
            ProtocolNegotiator negotiator,
            LifecycleCoordinator lifecycle,
            ExtensionEventHub events,
            com.javaclaw.server.persistence.TurnStreamService streams,
            com.javaclaw.server.preview.DocumentPreviewService previews,
            AutoCloseable resources)
            implements AutoCloseable {
        /** 校验组件。 */
        public Components {
            Objects.requireNonNull(json, "json");
            Objects.requireNonNull(router, "router");
            Objects.requireNonNull(negotiator, "negotiator");
            Objects.requireNonNull(lifecycle, "lifecycle");
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(resources, "resources");
        }

        /**
         * 为新连接创建独立协商状态。
         *
         * @return 会话
         */
        public AppServerSession newSession() {
            return new AppServerSession(negotiator, router, json, lifecycle, events, streams, previews);
        }

        /**
         * 等待所有客户端和活动 lease 消失后满 60 秒。
         *
         * @throws InterruptedException 当前线程被中断
         */
        public void awaitShutdownRequest() throws InterruptedException {
            lifecycle.awaitShutdownRequest();
        }

        /** 关闭 Turn 执行器和 Provider 客户端。 */
        @Override
        public void close() throws Exception {
            AppServerResources.closeInOrder(previews, resources, events, lifecycle);
        }
    }

    record Foundation(
            CanonicalJson json,
            H2Database database,
            com.javaclaw.server.persistence.TurnStreamService streams,
            com.javaclaw.server.preview.DocumentPreviewService previews,
            CoreCommandService core,
            PermissionProfileService permissionProfiles,
            ApprovalService approvals,
            ApprovalExpirationCoordinator approvalExpiration,
            ApprovalLifecycleCoordinator approvalLifecycle,
            AttachmentService attachments,
            ProviderService providers,
            ProviderCredentialService providerCredentials,
            EmbeddingBindingService embeddingBinding,
            AgentRoleService agentRoles,
            ExecutionConfigurationService executionConfigurations,
            SecretVaultService vault,
            ManagedWorktreeService worktrees,
            InputRequestService inputs,
            InputLifecycleCoordinator inputLifecycle,
            ExtensionJobService extensionJobs,
            ExtensionJobInputCoordinator extensionJobInputs,
            ExtensionCatalogRepository extensionCatalog,
            PrivateNetworkGrantService privateNetworkGrants,
            UnattendedToolGrantService unattendedToolGrants,
            SecurityGrantAuditService securityGrantAudit,
            ProjectInstructionResolver instructions,
            LifecycleCoordinator lifecycle,
            LoginStartupPort loginStartup,
            Clock clock,
            Instant startedAt) {}

    record RuntimeAssembly(
            TurnDispatcher dispatcher,
            ExtensionHost extensions,
            ThirdPartyExtensionHost thirdParty,
            ExtensionToolPlatform tools,
            RuntimeManagement management,
            AutoCloseable resources) {
        RuntimeAssembly {
            Objects.requireNonNull(dispatcher, "dispatcher");
            Objects.requireNonNull(extensions, "extensions");
            Objects.requireNonNull(thirdParty, "thirdParty");
            Objects.requireNonNull(tools, "tools");
            Objects.requireNonNull(management, "management");
            Objects.requireNonNull(resources, "resources");
        }
    }

    record RuntimeManagement(
            McpPlatformFactory.Services mcp,
            boolean mcpAvailable,
            BuiltinIsolatedServices.Availability workers,
            ScheduleLifecycleCoordinator scheduleLifecycle,
            ProviderModelDiscoveryService providerModelDiscovery,
            ProviderVerificationService providerVerification) {
        RuntimeManagement {
            Objects.requireNonNull(mcp, "mcp");
            Objects.requireNonNull(workers, "workers");
            Objects.requireNonNull(scheduleLifecycle, "scheduleLifecycle");
            Objects.requireNonNull(providerModelDiscovery, "providerModelDiscovery");
            Objects.requireNonNull(providerVerification, "providerVerification");
        }
    }
}
