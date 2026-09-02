package com.javaclaw.server;

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
import com.javaclaw.server.persistence.ExtensionJobSupervisor;
import com.javaclaw.server.persistence.H2ModelEventSink;
import com.javaclaw.server.persistence.H2TurnJournal;
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
            AppServerBootstrap.Foundation foundation,
            ModelGateway models,
            EmbeddingPort embeddings,
            IsolatedServicePort isolatedServices,
            McpRuntimePorts mcpPorts,
            StartupCloseStack startup) {
        RuntimeResources resources = ownRuntimeResources(models, embeddings, isolatedServices, startup);
        RuntimeState state = startRuntime(foundation, models, embeddings, isolatedServices, mcpPorts, startup);
        bindDeferredPorts(foundation, state);
        AppServerBootstrap.RuntimeAssembly runtime = assembly(foundation, mcpPorts, resources, state);
        AppServerBootstrap.Components result = AppServerBootstrap.components(foundation, runtime);
        startup.releaseAll();
        return result;
    }

    private static RuntimeState startRuntime(
            AppServerBootstrap.Foundation foundation,
            ModelGateway models,
            EmbeddingPort embeddings,
            IsolatedServicePort isolatedServices,
            McpRuntimePorts mcpPorts,
            StartupCloseStack startup) {
        ItemSchemaRegistry schemas = CoreItemCodecs.createRegistry(foundation.json());
        H2TurnJournal journal =
                new H2TurnJournal(foundation.database(), schemas, foundation.json(), foundation.clock());
        DeferredPorts deferred = new DeferredPorts();
        ScheduleLifecycleCoordinator scheduleLifecycle =
                startup.own(new ScheduleLifecycleCoordinator(foundation.lifecycle(), foundation.loginStartup()));
        BuiltinExtensionHost builtins =
                startBuiltins(foundation, embeddings, isolatedServices, startup, deferred, scheduleLifecycle);
        ThirdPartyExtensionHost thirdParty = startThirdParty(foundation, builtins, startup);
        PlatformExtensionHost extensions = ownPlatformHost(builtins, thirdParty, startup);
        McpPlatformFactory.Services mcp = McpPlatformFactory.create(
                new McpPlatformFactory.PlatformDependencies(
                        foundation.database(),
                        foundation.vault(),
                        foundation.privateNetworkGrants(),
                        foundation.extensionCatalog(),
                        foundation.lifecycle(),
                        foundation.json(),
                        foundation.clock()),
                new McpPlatformFactory.RuntimeDependencies(
                        mcpPorts,
                        thirdParty,
                        isolatedServices instanceof BuiltinIsolatedServices isolatedBuiltins
                                ? isolatedBuiltins.oauthBrowser()
                                : Optional.empty()));
        ExtensionToolPlatform tools = toolPlatform(foundation, extensions, mcp);
        DefaultTurnHarness harness = harness(foundation, models, schemas, journal, tools);
        ProviderVerificationService providerVerification = startup.own(new ProviderVerificationService(
                foundation.database(),
                foundation.providers(),
                foundation.vault(),
                models,
                foundation.json(),
                foundation.clock()));
        HarnessTurnDispatcher dispatcher = startup.own(dispatcher(foundation, models, journal, harness, tools));
        bindMcpInteractions(foundation, mcpPorts, harness);
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
                providerVerification,
                journal,
                jobs,
                scheduleLifecycle);
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
            ScheduleLifecycleCoordinator scheduleLifecycle) {
        return startup.own(AppServerExtensionBootstrap.start(
                foundation,
                new AppServerExtensionBootstrap.RuntimeDependencies(
                        deferred.orchestration(),
                        deferred.executionPolicies(),
                        isolatedServices,
                        embeddings,
                        deferred.automationSteps(),
                        deferred.scheduledCommands(),
                        scheduleLifecycle)));
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
        ServerTurnOrchestrationPort turns = new ServerTurnOrchestrationPort(
                foundation.core(),
                foundation.agentProfiles(),
                foundation.worktrees(),
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
            ExtensionToolPlatform tools) {
        TurnHarnessServices services = new TurnHarnessServices(
                models,
                new H2ConversationContext(foundation.core(), new ProviderStateService(foundation.database()), schemas),
                new BudgetContextCompactor(),
                tools,
                tools,
                journal,
                new H2ModelEventSink(foundation.database(), foundation.json(), foundation.clock()));
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
                        foundation.agentProfiles(),
                        foundation.profileBindings(),
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
            ProviderVerificationService providerVerification,
            H2TurnJournal journal,
            ExtensionJobSupervisor jobs,
            ScheduleLifecycleCoordinator scheduleLifecycle) {}
}
