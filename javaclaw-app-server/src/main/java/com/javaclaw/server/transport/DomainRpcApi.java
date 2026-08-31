package com.javaclaw.server.transport;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.automation.AutomationUseCases;
import com.javaclaw.agent.conversation.ProfileUseCases;
import com.javaclaw.agent.knowledge.KnowledgeUseCases;
import com.javaclaw.agent.runtime.AgentRuntime;
import com.javaclaw.agent.runtime.persistence.AttachmentRepository;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.server.configuration.ConfigurationUseCases;
import com.javaclaw.server.diagnostics.DiagnosticsUseCases;
import com.javaclaw.server.discovery.ServerDiscovery;
import com.javaclaw.server.extension.PluginUseCases;
import com.javaclaw.server.extension.mcp.McpUseCases;
import com.javaclaw.server.model.ProviderUseCases;

/** Immutable composition of domain handlers. Business services never return protocol DTOs. */
final class DomainRpcApi implements SessionRpcApi {
    private final AgentRuntime runtime;
    private final ServerDiscovery discovery;
    private final ConfigurationUseCases configuration;
    private final AttachmentRepository attachments;
    private final ApprovalResponseHandler approvals;
    private final UserInputResponseHandler userInputs;
    private final ProfileUseCases profiles;
    private final KnowledgeUseCases knowledge;
    private final AutomationUseCases automation;
    private final ProviderUseCases providers;
    private final PluginUseCases plugins;
    private final DiagnosticsUseCases diagnostics;
    private final McpUseCases mcp;
    private final com.javaclaw.agent.prompt.AgentsInstructionUseCases instructions;
    private final com.javaclaw.agent.conversation.ProfilePromptUseCases prompts;
    private final com.javaclaw.agent.conversation.PlanAdoptionUseCases plans;
    private final com.javaclaw.server.browser.BrowserSiteUseCases sites;
    private final com.javaclaw.server.network.NetworkGrantUseCases networkGrants;
    private final com.javaclaw.server.extension.ToolAuthorizationUseCases toolAuthorizations;
    private final com.javaclaw.agent.conversation.CompactionUseCases compaction;
    private final com.javaclaw.server.collaboration.WorktreeRecoveryUseCases worktreeRecovery;
    private final ObjectMapper json;
    private final ProtocolMapper wire;
    private final Map<String, Boolean> capabilities;

    DomainRpcApi(
            AgentRuntime runtime,
            ServerDiscovery discovery,
            ConfigurationUseCases configuration,
            AttachmentRepository attachments,
            ApprovalResponseHandler approvals,
            UserInputResponseHandler userInputs,
            ServerUseCases features,
            ObjectMapper json,
            boolean localSocket,
            boolean itemDelta) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.attachments = attachments;
        this.approvals = approvals;
        this.userInputs = userInputs;
        ServerUseCases enabled = features == null ? ServerUseCases.EMPTY : features;
        profiles = enabled.profiles();
        knowledge = enabled.knowledge();
        automation = enabled.automation();
        providers = enabled.providers();
        plugins = enabled.plugins();
        diagnostics = enabled.diagnostics();
        mcp = enabled.mcp();
        instructions = enabled.instructions();
        prompts = enabled.prompts();
        plans = enabled.plans();
        sites = enabled.sites();
        networkGrants = enabled.networkGrants();
        toolAuthorizations = enabled.toolAuthorizations();
        compaction = enabled.compaction();
        worktreeRecovery = enabled.worktreeRecovery();
        this.json = Objects.requireNonNull(json, "json");
        wire = new ProtocolMapper(json);
        capabilities = Map.ofEntries(
                Map.entry("threadTurnItem", true),
                Map.entry("durableEventReplay", true),
                Map.entry("stdio", true),
                Map.entry("localSocket", localSocket),
                Map.entry("attachments", attachments != null),
                Map.entry("itemDelta", itemDelta),
                Map.entry("profiles", profiles != null),
                Map.entry("agentsInstructions", instructions != null),
                Map.entry("promptOptimization", prompts != null),
                Map.entry("planAdoption", plans != null),
                Map.entry("modelCompaction", compaction != null),
                Map.entry("worktreeRecovery", worktreeRecovery != null),
                Map.entry("sites", sites != null),
                Map.entry("scopedPrivateNetwork", networkGrants != null),
                Map.entry("boundedToolAuthorization", toolAuthorizations != null),
                Map.entry("knowledge", knowledge != null),
                Map.entry("memory", knowledge != null),
                Map.entry("skills", knowledge != null),
                Map.entry("automation", automation != null),
                Map.entry("schedules", automation != null),
                Map.entry("providers", providers != null),
                Map.entry("pluginManagement", plugins != null),
                Map.entry("mcp20260728", mcp != null),
                Map.entry("diagnostics", diagnostics != null),
                Map.entry("approvals", approvals != null),
                Map.entry("userInput", userInputs != null));
    }

    @Override
    public RpcRouter router(ThreadSubscriptionAccess subscriptions, Consumer<JsonRpcNotification> notifications) {
        return new RpcRouter(List.of(
                new WorkspaceRpcHandler(runtime, instructions, json, wire),
                new ThreadRpcHandler(
                        runtime,
                        runtime,
                        runtime,
                        profiles,
                        plans,
                        compaction,
                        worktreeRecovery,
                        subscriptions,
                        json,
                        wire),
                new ModelRpcHandler(discovery, profiles, providers, prompts, json, wire),
                new AutomationRpcHandler(automation, json, wire),
                new KnowledgeRpcHandler(knowledge, json, wire),
                new ExtensionRpcHandler(
                        plugins,
                        mcp,
                        discovery,
                        json,
                        wire,
                        notifications,
                        new SiteRpcOperations(sites, networkGrants, json, wire),
                        new ToolAuthorizationRpcOperations(toolAuthorizations, json, wire)),
                new AttachmentRpcHandler(attachments, json, wire),
                new AdministrationRpcHandler(configuration, diagnostics, approvals, userInputs, json, wire)));
    }

    @Override
    public Map<String, Boolean> capabilities() {
        return capabilities;
    }
}
