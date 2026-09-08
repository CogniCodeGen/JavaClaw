package com.javaclaw.server;

import java.util.Objects;

import com.javaclaw.builtin.extensions.BuiltinExtensions;
import com.javaclaw.extension.spi.AutomationExecutionPolicyPort;
import com.javaclaw.extension.spi.AutomationStepPort;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.extension.spi.IsolatedServicePort;
import com.javaclaw.extension.spi.ScheduleLifecyclePort;
import com.javaclaw.extension.spi.ScheduledCommandPort;
import com.javaclaw.extension.spi.TurnOrchestrationPort;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.extension.BuiltinExtensionHost;
import com.javaclaw.server.extension.BuiltinExtensionRuntimePorts;
import com.javaclaw.server.extension.CanonicalExtensionPayloadCodec;
import com.javaclaw.server.extension.CoreAttachmentEvidencePort;
import com.javaclaw.server.extension.CoreItemEvidencePort;
import com.javaclaw.server.mcp.McpPlatformFactory;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.rpc.BuiltinExtensionRpcHandlers;
import com.javaclaw.server.rpc.McpRpcHandlers;
import com.javaclaw.server.rpc.RpcRouter;

/** 把内置扩展端口装配从主组合根中抽离，避免组合根演变为 Service Locator。 */
final class AppServerExtensionBootstrap {
    private AppServerExtensionBootstrap() {}

    static BuiltinExtensionHost start(AppServerBootstrap.Foundation foundation, RuntimeDependencies dependencies) {
        Objects.requireNonNull(foundation, "foundation");
        RuntimeDependencies runtime = Objects.requireNonNull(dependencies, "dependencies");
        CanonicalExtensionPayloadCodec payloads = new CanonicalExtensionPayloadCodec(foundation.json());
        var bindings = new com.javaclaw.server.extension.ServerScheduleDefinitionBindings(foundation.json());
        BuiltinExtensionRuntimePorts ports = new BuiltinExtensionRuntimePorts(
                foundation.clock(),
                payloads,
                new H2ManagedExtensionStore(foundation.database(), foundation.clock()),
                runtime.orchestration(),
                runtime.executionPolicies(),
                foundation.inputs(),
                foundation.extensionJobs(),
                new CoreItemEvidencePort(foundation.core(), foundation.json()),
                workspaceId -> new CoreAttachmentEvidencePort(foundation.attachments(), workspaceId),
                foundation.vault(),
                foundation.privateNetworkGrants(),
                runtime.services(),
                runtime.embeddings(),
                runtime.automationSteps(),
                runtime.scheduledCommands(),
                runtime.scheduleLifecycle(),
                foundation.extensionCatalog(),
                new com.javaclaw.server.persistence.ServerConversationEvidencePort(
                        foundation.database(), foundation.json()),
                bindings::forOwner);
        try {
            BuiltinExtensionHost host = BuiltinExtensionHost.start(
                    BuiltinExtensions.create(),
                    foundation.core(),
                    foundation.permissionProfiles(),
                    ports,
                    java.util.Optional.of(runtime.coding()));
            bindings.bindHost(host);
            return host;
        } catch (Exception failure) {
            throw new IllegalStateException("内置扩展启动失败", failure);
        }
    }

    static void registerManagement(
            RpcRouter.Builder routes,
            McpPlatformFactory.Services mcp,
            ExtensionCatalogRepository catalog,
            CanonicalJson json) {
        new McpRpcHandlers(mcp.service(), mcp.oauthCoordinator(), catalog, json).register(routes);
        new BuiltinExtensionRpcHandlers(catalog, json).register(routes);
    }

    /**
     * 内置扩展启动所需的非空运行端口；组合根一次性显式提供。
     *
     * @param orchestration Turn 编排
     * @param executionPolicies 执行政策
     * @param services 隔离服务
     * @param embeddings Embedding 路由
     * @param automationSteps 自动化步骤
     * @param scheduledCommands 调度命令
     * @param scheduleLifecycle 调度生命周期
     * @param coding 可信 Coding 平台
     */
    record RuntimeDependencies(
            TurnOrchestrationPort orchestration,
            AutomationExecutionPolicyPort executionPolicies,
            IsolatedServicePort services,
            EmbeddingPort embeddings,
            AutomationStepPort automationSteps,
            ScheduledCommandPort scheduledCommands,
            ScheduleLifecyclePort scheduleLifecycle,
            com.javaclaw.server.coding.CodingPlatform coding) {
        RuntimeDependencies {
            Objects.requireNonNull(orchestration, "orchestration");
            Objects.requireNonNull(executionPolicies, "executionPolicies");
            Objects.requireNonNull(services, "services");
            Objects.requireNonNull(embeddings, "embeddings");
            Objects.requireNonNull(automationSteps, "automationSteps");
            Objects.requireNonNull(scheduledCommands, "scheduledCommands");
            Objects.requireNonNull(scheduleLifecycle, "scheduleLifecycle");
            Objects.requireNonNull(coding, "coding");
        }
    }
}
