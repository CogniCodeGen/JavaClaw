package com.javaclaw.server;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.ItemSchemaRegistry;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.IsolatedServicePort;
import com.javaclaw.nativehost.sandbox.PlatformSandboxExecutor;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.runtime.DefaultTurnHarness;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.TurnHarnessServices;
import com.javaclaw.server.extension.BuiltinExtensionHost;
import com.javaclaw.server.extension.BuiltinIsolatedServices;
import com.javaclaw.server.extension.PlatformExtensionHost;
import com.javaclaw.server.extension.contract.ExtensionHost;
import com.javaclaw.server.extension.thirdparty.ThirdPartyExtensionHost;
import com.javaclaw.server.lifecycle.ScheduleLifecycleCoordinator;
import com.javaclaw.server.mcp.DeferredMcpClientInteractionFactory;
import com.javaclaw.server.mcp.McpPlatformFactory;
import com.javaclaw.server.mcp.McpRuntimePorts;
import com.javaclaw.server.mcp.McpSamplingTurnService;
import com.javaclaw.server.mcp.TurnMcpInteractionFactory;
import com.javaclaw.server.model.EmbeddingAdapterFactory;
import com.javaclaw.server.persistence.ExtensionJobSupervisor;
import com.javaclaw.server.persistence.H2ModelEventSink;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.ProviderModelDiscoveryService;
import com.javaclaw.server.persistence.ProviderStateService;
import com.javaclaw.server.persistence.ProviderVerificationService;
import com.javaclaw.server.security.PinnedHttpNetworkBroker;
import com.javaclaw.server.turn.BudgetContextCompactor;
import com.javaclaw.server.turn.CoreSystemInstruction;
import com.javaclaw.server.turn.DeferredAutomationExecutionPolicyPort;
import com.javaclaw.server.turn.DeferredAutomationStepPort;
import com.javaclaw.server.turn.DeferredScheduledCommandPort;
import com.javaclaw.server.turn.DeferredTurnOrchestrationPort;
import com.javaclaw.server.turn.ExtensionToolPlatform;
import com.javaclaw.server.turn.H2ConversationContext;
import com.javaclaw.server.turn.HarnessTurnDispatcher;
import com.javaclaw.server.turn.ServerAutomationStepPort;
import com.javaclaw.server.turn.ServerScheduledCommandPort;
import com.javaclaw.server.turn.ServerTurnOrchestrationPort;
import com.javaclaw.server.turn.TurnPlatformServices;

/** 装配 Turn Harness、扩展宿主与后台 Job 的运行时组合根。 */
final class AppServerRuntimeBootstrap {
    private AppServerRuntimeBootstrap() {}

    static AppServerBootstrap.Components create(
            AppServerBootstrap.Foundation foundation, RuntimeDependencies dependencies, StartupCloseStack startup) {
        RuntimeResources resources = ownRuntimeResources(
                dependencies.models(), dependencies.embeddings(), dependencies.isolatedServices(), startup);
        RuntimeState state = startRuntime(foundation, dependencies, startup);
        bindDeferredPorts(foundation, state);
        AppServerBootstrap.RuntimeAssembly runtime = assembly(foundation, dependencies.mcpPorts(), resources, state);
        AppServerBootstrap.Components result = AppServerBootstrap.components(foundation, runtime);
        startup.releaseAll();
        return result;
    }

    private static RuntimeState startRuntime(
            AppServerBootstrap.Foundation foundation, RuntimeDependencies dependencies, StartupCloseStack startup) {
        ItemSchemaRegistry schemas = CoreItemCodecs.createRegistry(foundation.json());
        H2TurnJournal journal = new H2TurnJournal(
                foundation.database(),
                schemas,
                foundation.json(),
                foundation.clock(),
                foundation.core().liveBudgets(),
                foundation.streams());
        DeferredPorts deferred = new DeferredPorts();
        ScheduleLifecycleCoordinator scheduleLifecycle =
                startup.own(new ScheduleLifecycleCoordinator(foundation.lifecycle(), foundation.loginStartup()));
        var coding = coding(foundation, startup);
        BuiltinExtensionHost builtins = startBuiltins(
                foundation,
                dependencies.embeddings(),
                dependencies.isolatedServices(),
                startup,
                deferred,
                scheduleLifecycle,
                coding);
        startup.release(coding);
        ThirdPartyExtensionHost thirdParty = startThirdParty(foundation, builtins, startup);
        PlatformExtensionHost extensions = ownPlatformHost(builtins, thirdParty, startup);
        McpPlatformFactory.Services mcp = mcp(foundation, dependencies, thirdParty);
        ExtensionToolPlatform tools = toolPlatform(foundation, extensions, mcp);
        DefaultTurnHarness harness = harness(foundation, dependencies.models(), schemas, journal, tools, coding);
        ProviderVerificationService providerVerification = providerVerification(foundation, dependencies, startup);
        ProviderModelDiscoveryService modelDiscovery = startup.own(dependencies.modelDiscovery());
        HarnessTurnDispatcher dispatcher =
                startup.own(dispatcher(foundation, dependencies.models(), journal, harness, tools));
        bindMcpInteractions(foundation, dependencies.mcpPorts(), harness);
        ExtensionJobSupervisor jobs = startup.own(new ExtensionJobSupervisor(
                foundation.extensionJobs(),
                job -> foundation.lifecycle().acquirePersistentActivity("job:" + job.id(), "EXTENSION_JOB")::close));
        return new RuntimeState(
                deferred,
                builtins,
                thirdParty,
                extensions,
                mcp,
                tools,
                dispatcher,
                modelDiscovery,
                providerVerification,
                journal,
                jobs,
                scheduleLifecycle);
    }

    private static McpPlatformFactory.Services mcp(
            AppServerBootstrap.Foundation foundation,
            RuntimeDependencies dependencies,
            ThirdPartyExtensionHost thirdParty) {
        return McpPlatformFactory.create(
                new McpPlatformFactory.PlatformDependencies(
                        foundation.database(),
                        foundation.vault(),
                        foundation.privateNetworkGrants(),
                        foundation.extensionCatalog(),
                        foundation.lifecycle(),
                        foundation.json(),
                        foundation.clock()),
                new McpPlatformFactory.RuntimeDependencies(
                        dependencies.mcpPorts(),
                        thirdParty,
                        dependencies.isolatedServices() instanceof BuiltinIsolatedServices isolatedBuiltins
                                ? isolatedBuiltins.oauthBrowser()
                                : Optional.empty()));
    }

    private static ProviderVerificationService providerVerification(
            AppServerBootstrap.Foundation foundation, RuntimeDependencies dependencies, StartupCloseStack startup) {
        return startup.own(new ProviderVerificationService(
                foundation.database(),
                foundation.providers(),
                foundation.vault(),
                dependencies.models(),
                dependencies.embeddingAdapters(),
                foundation.json(),
                foundation.clock()));
    }

    private static void bindMcpInteractions(
            AppServerBootstrap.Foundation foundation, McpRuntimePorts ports, DefaultTurnHarness harness) {
        if (ports.interactions() instanceof DeferredMcpClientInteractionFactory deferred) {
            McpSamplingTurnService sampling =
                    new McpSamplingTurnService(foundation.core(), harness, foundation.json(), foundation.clock());
            deferred.bind(new TurnMcpInteractionFactory(
                    foundation.inputs(), foundation.core(), sampling, foundation.json(), foundation.clock()));
        }
    }

    private static BuiltinExtensionHost startBuiltins(
            AppServerBootstrap.Foundation foundation,
            EmbeddingPort embeddings,
            IsolatedServicePort isolatedServices,
            StartupCloseStack startup,
            DeferredPorts deferred,
            ScheduleLifecycleCoordinator scheduleLifecycle,
            com.javaclaw.server.coding.CodingPlatform coding) {
        return startup.own(AppServerExtensionBootstrap.start(
                foundation,
                new AppServerExtensionBootstrap.RuntimeDependencies(
                        deferred.orchestration(),
                        deferred.executionPolicies(),
                        isolatedServices,
                        embeddings,
                        deferred.automationSteps(),
                        deferred.scheduledCommands(),
                        scheduleLifecycle,
                        coding)));
    }

    private static com.javaclaw.server.coding.CodingPlatform coding(
            AppServerBootstrap.Foundation foundation, StartupCloseStack startup) {
        try {
            var toolchains = startup.own(new com.javaclaw.server.coding.ToolchainManager(
                    new com.javaclaw.server.coding.ToolchainManager.Dependencies(
                            foundation.database(),
                            foundation.extensionJobs(),
                            com.javaclaw.server.toolchain.CodingToolchainCatalog.bundled(),
                            foundation.json(),
                            foundation.clock(),
                            new com.javaclaw.server.security.PinnedArtifactDownloader())));
            var platform = startup.own(new com.javaclaw.server.coding.CodingPlatform(
                    new com.javaclaw.server.coding.CodingPlatform.Dependencies(
                            foundation.database(),
                            foundation.core(),
                            new com.javaclaw.server.turn.CodingExecutionAuthority(
                                    foundation.core(),
                                    foundation.permissionProfiles(),
                                    foundation.worktrees(),
                                    foundation.extensionCatalog(),
                                    foundation.json()),
                            foundation.attachments(),
                            toolchains,
                            new PlatformSandboxExecutor(),
                            foundation.json(),
                            foundation.clock())));
            startup.release(toolchains);
            return platform;
        } catch (Exception failure) {
            throw new IllegalStateException("Coding 平台装配失败", failure);
        }
    }

    private static ThirdPartyExtensionHost startThirdParty(
            AppServerBootstrap.Foundation foundation, BuiltinExtensionHost builtins, StartupCloseStack startup) {
        return startup.own(ThirdPartyExtensionHost.start(
                foundation.database(),
                foundation.json(),
                foundation.clock(),
                new ThirdPartyExtensionHost.ExecutionPorts(
                        new PlatformSandboxExecutor(), new PinnedHttpNetworkBroker()),
                foundation.core(),
                builtins.tools(),
                foundation.attachments()));
    }

    private static PlatformExtensionHost ownPlatformHost(
            BuiltinExtensionHost builtins, ThirdPartyExtensionHost thirdParty, StartupCloseStack startup) {
        PlatformExtensionHost extensions = new PlatformExtensionHost(builtins, thirdParty);
        startup.release(builtins);
        startup.release(thirdParty);
        return startup.own(extensions);
    }

    private static void bindDeferredPorts(AppServerBootstrap.Foundation foundation, RuntimeState state) {
        state.tools().bindCollaboration(AppServerBootstrap.collaboration(foundation, state.dispatcher()));
        ServerTurnOrchestrationPort turns = new ServerTurnOrchestrationPort(
                new TurnPlatformServices(
                        foundation.core(),
                        foundation.agentRoles(),
                        foundation.executionConfigurations(),
                        foundation.permissionProfiles(),
                        foundation.instructions(),
                        foundation.worktrees()),
                foundation.providers(),
                state.dispatcher(),
                foundation.json());
        state.deferred().orchestration().bind(turns);
        state.deferred().executionPolicies().bind(turns);
        state.deferred()
                .automationSteps()
                .bind(new ServerAutomationStepPort(new ServerAutomationStepPort.Dependencies(
                        foundation.core(),
                        foundation.worktrees(),
                        state.dispatcher(),
                        state.journal(),
                        state.tools(),
                        foundation.inputs(),
                        foundation.json(),
                        foundation.clock())));
        state.deferred().scheduledCommands().bind(new ServerScheduledCommandPort(state.builtins()));
        foundation.approvalLifecycle().bindResume(state.dispatcher()::resume);
        state.builtins().registerJobExecutors(state.jobs());
        restoreExtensions(state.builtins());
        state.dispatcher().resumePersisted();
        state.jobs().start();
    }

    private static AppServerBootstrap.RuntimeAssembly assembly(
            AppServerBootstrap.Foundation foundation,
            McpRuntimePorts mcpPorts,
            RuntimeResources resources,
            RuntimeState state) {
        resources.releaseModel();
        return new AppServerBootstrap.RuntimeAssembly(
                state.dispatcher(),
                state.extensions(),
                state.thirdParty(),
                state.tools(),
                new AppServerBootstrap.RuntimeManagement(
                        state.mcp(),
                        mcpPorts.available(),
                        resources.workerAvailability(),
                        state.scheduleLifecycle(),
                        state.modelDiscovery(),
                        state.providerVerification()),
                new AppServerResources(
                        state.jobs(),
                        foundation.extensionJobInputs(),
                        foundation.inputLifecycle(),
                        foundation.approvalExpiration(),
                        foundation.approvalLifecycle(),
                        new RuntimeTurnResources(state.dispatcher(), state.providerVerification()),
                        foundation.approvals(),
                        state.extensions(),
                        state.scheduleLifecycle(),
                        state.modelDiscovery(),
                        resources.embeddings(),
                        foundation.vault(),
                        resources.services()));
    }

    private static RuntimeResources ownRuntimeResources(
            ModelGateway models,
            EmbeddingPort embeddings,
            IsolatedServicePort isolatedServices,
            StartupCloseStack startup) {
        AutoCloseable modelResource = startup.own(closeable(models));
        AutoCloseable embeddingResource = startup.own(closeable(embeddings));
        AutoCloseable serviceResource = startup.own(closeable(isolatedServices));
        BuiltinIsolatedServices.Availability availability = isolatedServices instanceof BuiltinIsolatedServices builtins
                ? builtins.availability()
                : new BuiltinIsolatedServices.Availability(false, false, false);
        return new RuntimeResources(startup, modelResource, embeddingResource, serviceResource, availability);
    }

    private static ExtensionToolPlatform toolPlatform(
            AppServerBootstrap.Foundation foundation, ExtensionHost extensions, McpPlatformFactory.Services mcp) {
        return new ExtensionToolPlatform(new ExtensionToolPlatform.Dependencies(
                extensions,
                foundation.core(),
                foundation.approvals(),
                foundation.permissionProfiles(),
                foundation.worktrees(),
                foundation.extensionCatalog(),
                foundation.json(),
                foundation.clock(),
                foundation.unattendedToolGrants(),
                Optional.of(mcp.service())));
    }

    private static DefaultTurnHarness harness(
            AppServerBootstrap.Foundation foundation,
            ModelGateway models,
            ItemSchemaRegistry schemas,
            H2TurnJournal journal,
            ExtensionToolPlatform tools,
            com.javaclaw.server.coding.CodingPlatform coding) {
        TurnHarnessServices services = new TurnHarnessServices(
                models,
                new H2ConversationContext(
                        foundation.core(), new ProviderStateService(foundation.database()), schemas, models),
                new BudgetContextCompactor(),
                tools,
                tools,
                journal,
                new H2ModelEventSink(
                        foundation.database(), foundation.json(), foundation.clock(), foundation.streams()),
                coding);
        return new DefaultTurnHarness(services, foundation.clock());
    }

    private static HarnessTurnDispatcher dispatcher(
            AppServerBootstrap.Foundation foundation,
            ModelGateway models,
            H2TurnJournal journal,
            DefaultTurnHarness harness,
            ExtensionToolPlatform tools) {
        return new HarnessTurnDispatcher(
                new TurnPlatformServices(
                        foundation.core(),
                        foundation.agentRoles(),
                        foundation.executionConfigurations(),
                        foundation.permissionProfiles(),
                        foundation.instructions(),
                        foundation.worktrees()),
                new HarnessTurnDispatcher.RuntimeResources(models, harness, journal, foundation.lifecycle(), tools),
                CoreSystemInstruction.load(),
                foundation.clock(),
                foundation.json());
    }

    private static AutoCloseable closeable(Object resource) {
        return resource instanceof AutoCloseable closeable ? closeable : () -> {};
    }

    private static void restoreExtensions(BuiltinExtensionHost extensions) {
        try {
            extensions.restore();
        } catch (Exception failure) {
            throw new IllegalStateException("内置扩展后台状态恢复失败", failure);
        }
    }

    /**
     * 汇总运行时组合根所需的模型、Embedding、隔离服务与 MCP 边界。
     *
     * <p>该值只描述依赖关系，不接管资源；资源所有权仍由 {@link StartupCloseStack} 按既有顺序管理。
     *
     * @param models 模型调用路由
     * @param embeddings Embedding 调用路由
     * @param embeddingAdapters Provider 验证使用的 Embedding Adapter 工厂
     * @param modelDiscovery Provider 模型目录发现服务
     * @param isolatedServices 受控进程外服务路由
     * @param mcpPorts MCP 远端、交互、网络和 OAuth 边界
     */
    record RuntimeDependencies(
            ModelGateway models,
            EmbeddingPort embeddings,
            EmbeddingAdapterFactory embeddingAdapters,
            ProviderModelDiscoveryService modelDiscovery,
            IsolatedServicePort isolatedServices,
            McpRuntimePorts mcpPorts) {
        RuntimeDependencies {
            Objects.requireNonNull(models, "models");
            Objects.requireNonNull(embeddings, "embeddings");
            Objects.requireNonNull(embeddingAdapters, "embeddingAdapters");
            Objects.requireNonNull(modelDiscovery, "modelDiscovery");
            Objects.requireNonNull(isolatedServices, "isolatedServices");
            Objects.requireNonNull(mcpPorts, "mcpPorts");
        }
    }

    private record DeferredPorts(
            DeferredTurnOrchestrationPort orchestration,
            DeferredAutomationExecutionPolicyPort executionPolicies,
            DeferredAutomationStepPort automationSteps,
            DeferredScheduledCommandPort scheduledCommands) {
        private DeferredPorts() {
            this(
                    new DeferredTurnOrchestrationPort(),
                    new DeferredAutomationExecutionPolicyPort(),
                    new DeferredAutomationStepPort(),
                    new DeferredScheduledCommandPort());
        }
    }

    private record RuntimeResources(
            StartupCloseStack startup,
            AutoCloseable model,
            AutoCloseable embeddings,
            AutoCloseable services,
            BuiltinIsolatedServices.Availability workerAvailability) {
        private void releaseModel() {
            startup.release(model);
        }
    }

    private record RuntimeState(
            DeferredPorts deferred,
            BuiltinExtensionHost builtins,
            ThirdPartyExtensionHost thirdParty,
            PlatformExtensionHost extensions,
            McpPlatformFactory.Services mcp,
            ExtensionToolPlatform tools,
            HarnessTurnDispatcher dispatcher,
            ProviderModelDiscoveryService modelDiscovery,
            ProviderVerificationService providerVerification,
            H2TurnJournal journal,
            ExtensionJobSupervisor jobs,
            ScheduleLifecycleCoordinator scheduleLifecycle) {}
}
