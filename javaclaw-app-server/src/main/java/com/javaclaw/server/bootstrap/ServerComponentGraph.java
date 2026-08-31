package com.javaclaw.server.bootstrap;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.automation.AutomationRuntime;
import com.javaclaw.agent.conversation.ProfileRepository;
import com.javaclaw.agent.conversation.ProfileService;
import com.javaclaw.agent.kernel.AgentLoopKernel;
import com.javaclaw.agent.kernel.RoutingAgentKernel;
import com.javaclaw.agent.knowledge.KnowledgeService;
import com.javaclaw.agent.runtime.DefaultAgentRuntime;
import com.javaclaw.agent.runtime.RuntimeEventBus;
import com.javaclaw.agent.tool.FirstPartyToolProvider;
import com.javaclaw.agent.tool.GovernedToolRuntime;
import com.javaclaw.agent.tool.LauncherProcessSandboxExecutor;
import com.javaclaw.agent.tool.PendingApprovalGateway;
import com.javaclaw.agent.tool.PendingUserInputGateway;
import com.javaclaw.agent.tools.BrokeredWebFetchTool;
import com.javaclaw.agent.tools.BrowserSnapshotTool;
import com.javaclaw.agent.tools.CollaborationTools;
import com.javaclaw.agent.tools.SandboxedCommandTool;
import com.javaclaw.agent.tools.UserInputTool;
import com.javaclaw.sandbox.api.NetworkPolicy;
import com.javaclaw.sandbox.api.SandboxMode;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.server.browser.BrowserServiceRuntime;
import com.javaclaw.server.collaboration.CollaborationService;
import com.javaclaw.server.collaboration.DeferredCollaborationGateway;
import com.javaclaw.server.configuration.ServerConfiguration;
import com.javaclaw.server.diagnostics.DiagnosticsService;
import com.javaclaw.server.discovery.CompositeServerDiscovery;
import com.javaclaw.server.discovery.ModelToolServerDiscovery;
import com.javaclaw.server.discovery.PluginServerDiscovery;
import com.javaclaw.server.discovery.ServerDiscovery;
import com.javaclaw.server.extension.Ed25519PluginSignatureVerifier;
import com.javaclaw.server.extension.PluginBundleInstaller;
import com.javaclaw.server.extension.PluginBundleLoader;
import com.javaclaw.server.extension.PluginCatalog;
import com.javaclaw.server.extension.PluginService;
import com.javaclaw.server.extension.PluginToolHooks;
import com.javaclaw.server.extension.mcp.DefaultMcpInputResolver;
import com.javaclaw.server.extension.mcp.DefaultMcpTransportFactory;
import com.javaclaw.server.extension.mcp.McpClientRegistry;
import com.javaclaw.server.extension.mcp.McpCodec;
import com.javaclaw.server.extension.mcp.McpOAuthService;
import com.javaclaw.server.extension.mcp.McpService;
import com.javaclaw.server.extension.mcp.McpToolProvider;
import com.javaclaw.server.model.ProviderService;
import com.javaclaw.server.model.ReloadableCloudModelGateway;
import com.javaclaw.server.network.HttpNetworkBroker;
import com.javaclaw.server.persistence.H2AutomationRepository;
import com.javaclaw.server.persistence.H2CollaborationRepository;
import com.javaclaw.server.persistence.H2DiagnosticsRepository;
import com.javaclaw.server.persistence.H2KnowledgeRepository;
import com.javaclaw.server.persistence.H2McpRepository;
import com.javaclaw.server.persistence.H2Persistence;
import com.javaclaw.server.persistence.H2PluginRepository;
import com.javaclaw.server.persistence.H2PluginTrustStore;
import com.javaclaw.server.persistence.H2ProfileRepository;
import com.javaclaw.server.persistence.H2ProviderConfigStore;
import com.javaclaw.server.persistence.H2WorktreeRepository;
import com.javaclaw.server.sandbox.WorkspaceLockingSandboxExecutor;
import com.javaclaw.server.transport.AppServerEndpointConfig;
import com.javaclaw.server.transport.ApprovalResponseHandler;
import com.javaclaw.server.transport.LocalSocketAppServer;
import com.javaclaw.server.transport.MultiplexedAppServer;
import com.javaclaw.server.transport.ServerUseCases;
import com.javaclaw.server.transport.StdioAppServer;
import com.javaclaw.server.transport.UserInputResponseHandler;

/** Process-scoped composition root with deterministic ownership and shutdown order. */
public final class ServerComponentGraph implements AutoCloseable {
    private final String[] args;
    private final ServerDirectories directories;
    private final H2Persistence persistence;
    private boolean served;
    private boolean closed;

    private ServerComponentGraph(String[] args, ServerDirectories directories, H2Persistence persistence) {
        this.args = args.clone();
        this.directories = java.util.Objects.requireNonNull(directories, "directories");
        this.persistence = java.util.Objects.requireNonNull(persistence, "persistence");
    }

    /**
     * 从启动参数装配唯一 App Server 运行时，打开 v4 H2 并创建受治理工具/扩展；调用方必须关闭返回组件图。
     *
     * @throws Exception 数据格式、安全能力或配置不合法
     */
    public static ServerComponentGraph create(String[] args) {
        String[] safe = args == null ? new String[0] : args.clone();
        ServerDirectories directories = ServerDirectories.resolve(safe);
        return new ServerComponentGraph(safe, directories, new H2Persistence(directories.dataRoot()));
    }

    /**
     * 按启动配置运行 stdio、UDS 或 Windows Mux 服务，阻塞直到输入结束或服务关闭。stdio 使用独立协议流，不读取可能被诊断日志使用的 System.out。
     *
     * @throws Exception 传输失败
     */
    public synchronized void serve(java.io.InputStream protocolInput, java.io.OutputStream protocolOutput)
            throws Exception {
        java.util.Objects.requireNonNull(protocolInput, "protocolInput");
        java.util.Objects.requireNonNull(protocolOutput, "protocolOutput");
        if (closed) {
            throw new IllegalStateException("server component graph is closed");
        }
        if (served) {
            throw new IllegalStateException("server component graph can only serve once");
        }
        served = true;
        serve(args, directories, persistence, protocolInput, protocolOutput);
    }

    private static void serve(
            String[] args,
            ServerDirectories directories,
            H2Persistence persistence,
            java.io.InputStream protocolInput,
            java.io.OutputStream protocolOutput)
            throws Exception {
        persistence.attachments().reconcileAttachments();
        persistence.attachments().collectExpiredUploads();
        Path dataRoot = persistence.dataRoot();
        ObjectMapper json = new ObjectMapper();
        Path configRoot = directories.configurationRoot();
        Path cacheRoot = directories.cacheRoot();
        ServerConfiguration serverConfiguration = ServerConfiguration.persistent(json, persistence.configuration());
        var instructionResolver = new com.javaclaw.agent.prompt.AgentsInstructionResolver(
                configRoot, () -> agentsInstructionSettings(serverConfiguration, json));
        var secrets = persistence.secretStore(configRoot);
        ReloadableCloudModelGateway models = new ReloadableCloudModelGateway(secrets, System.getenv());
        RuntimeEventBus events = new RuntimeEventBus();
        PendingApprovalGateway approvals = new PendingApprovalGateway(Duration.ofMinutes(10), 64);
        PendingUserInputGateway userInputs = new PendingUserInputGateway(Duration.ofHours(1), 64);
        SandboxPolicy ceiling = hostCeiling(dataRoot, configRoot);
        ProfileService profiles =
                new ProfileService(new H2ProfileRepository(persistence.database()), ceiling.protectedRoots());
        ensureDefaultProfiles(profiles);
        ProviderService providers =
                new ProviderService(new H2ProviderConfigStore(persistence.database()), secrets, models, profiles);
        String launcherModules = System.getenv("JAVACLAW_SANDBOX_MODULE_PATH");
        com.javaclaw.sandbox.api.SandboxExecutor launcherSandbox = launcherModules == null || launcherModules.isBlank()
                ? (com.javaclaw.sandbox.api.SandboxExecutor) command -> {
                    throw new IllegalStateException("sandbox launcher is not configured");
                }
                : new LauncherProcessSandboxExecutor(
                        LauncherProcessSandboxExecutor.modularJavaCommand(sandboxModulePaths(launcherModules)));
        com.javaclaw.sandbox.api.SandboxExecutor sandbox =
                new WorkspaceLockingSandboxExecutor(launcherSandbox, persistence.workspaces());
        var workers = new com.javaclaw.server.execution.FixedJvmWorkers(
                sandbox, cacheRoot.resolve("document-worker"), ceiling.protectedRoots());
        var knowledgeRepository = new H2KnowledgeRepository(persistence.database());
        var maintenanceRepository =
                new com.javaclaw.server.persistence.H2KnowledgeMaintenanceRepository(persistence.database());
        String embeddingProvider = environment("JAVACLAW_EMBEDDING_PROVIDER", "openai");
        String embeddingModelOverride = System.getenv("JAVACLAW_EMBEDDING_MODEL");
        java.util.function.Supplier<String> embeddingModel =
                embeddingModelOverride == null || embeddingModelOverride.isBlank()
                        ? () -> configuredEmbeddingModel(providers, embeddingProvider)
                        : () -> embeddingModelOverride.strip();
        KnowledgeService knowledge = KnowledgeService.withReloadableEmbeddingModel(
                knowledgeRepository, persistence.attachments(), models, workers, embeddingProvider, embeddingModel);
        H2PluginTrustStore pluginTrust = new H2PluginTrustStore(persistence.database());
        PluginCatalog plugins =
                new PluginCatalog(new PluginBundleLoader(new Ed25519PluginSignatureVerifier(pluginTrust)));
        Path pluginRoot = dataRoot.resolve("plugins");
        PluginBundleInstaller pluginInstaller = new PluginBundleInstaller(
                pluginRoot, dataRoot.resolve("plugin-staging"), dataRoot.resolve("Trash/plugins"), plugins);
        java.util.ArrayList<com.javaclaw.agent.tool.RegisteredTool> toolList = new java.util.ArrayList<>();
        DeferredCollaborationGateway deferredCollaboration = new DeferredCollaborationGateway();
        HttpNetworkBroker networkBroker = new HttpNetworkBroker();
        var networkGrants = new com.javaclaw.server.network.NetworkGrantService(
                new com.javaclaw.server.persistence.H2NetworkGrantRepository(persistence.database()),
                persistence.workspaces());
        toolList.add(UserInputTool.create(userInputs, ceiling));
        toolList.add(BrokeredWebFetchTool.scoped(
                call -> networkGrants.broker(call.thread().workspaceId(), "WEB"), ceiling));
        toolList.addAll(CollaborationTools.create(deferredCollaboration, ceiling));
        BrowserServiceRuntime browserRuntime;
        List<String> browserCommand = browserServiceCommand(json);
        if (launcherModules != null && !launcherModules.isBlank() && !browserCommand.isEmpty()) {
            browserRuntime = new BrowserServiceRuntime(
                    networkBroker,
                    persistence.attachments(),
                    sandbox,
                    ceiling,
                    browserCommand,
                    cacheRoot.resolve("browser-service"),
                    browserInfrastructureRoots(browserCommand),
                    json);
            toolList.add(BrowserSnapshotTool.create(browserRuntime, ceiling));
        } else {
            browserRuntime = null;
        }
        var browserSites = new com.javaclaw.server.browser.BrowserSiteService(
                new com.javaclaw.server.persistence.H2BrowserSiteRepository(persistence.database()),
                persistence.workspaces(),
                persistence.attachments(),
                persistence.journal(),
                secrets,
                browserRuntime,
                networkGrants);
        if (launcherModules != null && !launcherModules.isBlank()) {
            toolList.add(SandboxedCommandTool.create(ceiling));
            toolList.addAll(com.javaclaw.agent.tools.WorkspaceFileTools.create(workers, ceiling));
            toolList.add(com.javaclaw.agent.tools.WorkspaceFileTools.javaCode(workers, ceiling));
        }
        List<com.javaclaw.agent.tool.RegisteredTool> registeredTools = List.copyOf(toolList);
        var pluginProcesses = new com.javaclaw.server.extension.PluginProcessRuntime(plugins, sandbox, json);
        H2McpRepository mcpRepository = new H2McpRepository(persistence.database());
        PluginService pluginService = new PluginService(
                new H2PluginRepository(persistence.database()),
                pluginTrust,
                mcpRepository,
                plugins,
                pluginInstaller,
                pluginProcesses,
                persistence.attachments(),
                ceiling,
                json,
                pluginRoot);
        var pluginHooks = new PluginToolHooks(plugins, pluginProcesses, json);
        McpCodec mcpCodec = new McpCodec(json);
        McpOAuthService mcpOAuth = new McpOAuthService(
                configuration -> configuration.workspaceId() == null
                        ? networkBroker
                        : networkGrants.broker(configuration.workspaceId(), "OAUTH"),
                secrets,
                json);
        McpClientRegistry mcpClients = new McpClientRegistry(
                mcpRepository,
                json,
                mcpCodec,
                new DefaultMcpTransportFactory(
                        plugins,
                        sandbox,
                        (configuration, turn) ->
                                networkGrants.broker(turn.thread().workspaceId(), "MCP"),
                        secrets,
                        ceiling,
                        json,
                        mcpCodec,
                        mcpOAuth),
                new DefaultMcpInputResolver(userInputs, models, json));
        McpService mcpService = new McpService(mcpRepository, mcpClients, secrets, json, mcpOAuth);
        var toolAuthorizations = new com.javaclaw.server.extension.ToolAuthorizationService(
                new com.javaclaw.server.persistence.H2ToolAuthorizationRepository(persistence.database()),
                persistence.workspaces(),
                mcpClients::authorityOptions,
                json);
        var toolProviders = new java.util.ArrayList<com.javaclaw.agent.tool.ToolProvider>(List.of(
                new FirstPartyToolProvider("first-party", registeredTools),
                new com.javaclaw.agent.knowledge.SkillToolProvider(
                        knowledge, ceiling, launcherModules == null || launcherModules.isBlank() ? null : workers),
                new McpToolProvider(mcpClients, json)));
        if (launcherModules != null && !launcherModules.isBlank()) {
            toolProviders.add(new com.javaclaw.agent.tool.TerminalToolProvider(ceiling));
            toolProviders.add(new com.javaclaw.agent.tool.DocumentToolProvider(
                    persistence.attachments(), workers, models, ceiling));
        }
        if (browserRuntime != null) {
            toolProviders.add(new com.javaclaw.agent.tool.BrowserToolProvider(browserSites, ceiling));
        }
        GovernedToolRuntime tools = new GovernedToolRuntime(
                toolProviders,
                pluginHooks.preHooks(),
                pluginHooks.postHooks(),
                approvals,
                sandbox,
                ceiling,
                Duration.ofSeconds(2),
                json,
                toolAuthorizations);
        ServerDiscovery discovery = new CompositeServerDiscovery(List.of(
                new ModelToolServerDiscovery(
                        models::descriptors,
                        () -> toolProviders.stream()
                                .flatMap(provider -> provider.catalog().stream())
                                .toList()),
                new PluginServerDiscovery(plugins)));
        try (models;
                approvals;
                userInputs;
                tools;
                mcpService;
                mcpClients;
                pluginProcesses;
                browserRuntime;
                browserSites;
                DefaultAgentRuntime threads = new DefaultAgentRuntime(
                        persistence.runtime(),
                        new RoutingAgentKernel(new AgentLoopKernel(
                                models,
                                tools,
                                List.of(knowledge),
                                new com.javaclaw.agent.context.ContentAddressedInputResolver(
                                        persistence.attachments(), workers),
                                deferredCollaboration,
                                userInputs,
                                new com.javaclaw.agent.knowledge.KnowledgeMaintenanceExecution(
                                        knowledgeRepository, maintenanceRepository),
                                instructionResolver)),
                        events)) {
            CollaborationService collaboration = new CollaborationService(
                    threads,
                    threads,
                    threads,
                    threads,
                    profiles,
                    new H2CollaborationRepository(persistence.database()),
                    new H2WorktreeRepository(persistence.database()),
                    persistence.attachments(),
                    sandbox,
                    ceiling,
                    cacheRoot.resolve("worktrees"),
                    controlledGitCommand());
            deferredCollaboration.bind(collaboration);
            collaboration.reconcile();
            try (var maintenance = new com.javaclaw.agent.knowledge.KnowledgeMaintenanceScheduler(
                            maintenanceRepository, knowledgeRepository, threads, threads, threads);
                    AutomationRuntime automation = new AutomationRuntime(
                            new H2AutomationRepository(persistence.database()),
                            threads,
                            threads,
                            threads,
                            (profileId, workspace, requiredKind) -> {
                                var resolved = profiles.resolve(
                                        profileId,
                                        workspace,
                                        requiredKind == com.javaclaw.core.api.ProfileKind.SCHEDULE
                                                ? com.javaclaw.core.api.ApprovalPolicy.NEVER
                                                : com.javaclaw.core.api.ApprovalPolicy.ON_RISK,
                                        "medium");
                                if (resolved.profile().kind() != requiredKind) {
                                    throw new IllegalArgumentException(
                                            "profile " + profileId + " must have kind " + requiredKind);
                                }
                                return resolved;
                            })) {
                automation.start();
                maintenance.start();
                DiagnosticsService diagnostics = new DiagnosticsService(
                        new H2DiagnosticsRepository(persistence.database()), persistence.attachments(), json);
                ServerUseCases features = new ServerUseCases(
                        profiles,
                        knowledge,
                        automation,
                        providers,
                        pluginService,
                        diagnostics,
                        mcpService,
                        instructionResolver,
                        new com.javaclaw.agent.conversation.ProfilePromptService(
                                profiles, threads, threads, threads, discovery::tools),
                        new com.javaclaw.agent.conversation.PlanAdoptionService(profiles, threads, threads, threads),
                        browserSites,
                        networkGrants,
                        toolAuthorizations,
                        new com.javaclaw.agent.conversation.CompactionService(threads, threads),
                        collaboration);
                ApprovalResponseHandler approvalHandler = (approvalId, approved) -> {
                    if (!approvals.hasPending(approvalId)) {
                        return false;
                    }
                    if (!threads.respondToApproval(approvalId, approved)) {
                        return false;
                    }
                    return approvals.respond(approvalId, approved);
                };
                UserInputResponseHandler userInputHandler = (requestId, value, cancelled) -> {
                    if (!userInputs.hasPending(requestId)) {
                        return false;
                    }
                    if (!threads.respondToUserInput(requestId, value, cancelled)) {
                        return false;
                    }
                    return userInputs.respond(requestId, value, cancelled);
                };
                Path socket = socketPath(args);
                boolean windowsMux = hasFlag(args, "--windows-mux");
                if (socket != null && windowsMux) {
                    throw new IllegalArgumentException("--socket and --windows-mux are mutually exclusive");
                }
                AppServerEndpointConfig endpoint = new AppServerEndpointConfig(
                        threads,
                        events,
                        new com.javaclaw.protocol.JsonRpcCodec(),
                        StdioAppServer.DEFAULT_MAX_FRAME_CHARS,
                        approvalHandler,
                        userInputHandler,
                        discovery,
                        windowsMux,
                        serverConfiguration,
                        persistence.attachments(),
                        threads.liveItemEvents(),
                        features);
                StdioAppServer connectionServer = new StdioAppServer(endpoint);
                if (windowsMux) {
                    new MultiplexedAppServer(connectionServer, json)
                            .serve(
                                    new InputStreamReader(protocolInput, StandardCharsets.UTF_8),
                                    new OutputStreamWriter(protocolOutput, StandardCharsets.UTF_8));
                } else if (socket == null) {
                    connectionServer.serve(
                            new InputStreamReader(protocolInput, StandardCharsets.UTF_8),
                            new OutputStreamWriter(protocolOutput, StandardCharsets.UTF_8));
                } else {
                    try (LocalSocketAppServer server = new LocalSocketAppServer(socket, endpoint)) {
                        server.serve();
                    }
                }
            }
        }
    }

    private static void ensureDefaultProfiles(ProfileService profiles) {
        if (profiles.list().stream().noneMatch(value -> "profile_chat".equals(value.id()))) {
            profiles.put(
                    new ProfileRepository.ProfileDraft(
                            "profile_chat",
                            "Chat",
                            com.javaclaw.core.api.ProfileKind.CHAT,
                            "openai",
                            "gpt-5",
                            "",
                            Set.of(),
                            SandboxMode.READ_ONLY,
                            16,
                            16,
                            java.util.Map.of()),
                    0,
                    "builtin-profile-chat");
        }
        if (profiles.list().stream().noneMatch(value -> "profile_plan".equals(value.id()))) {
            profiles.put(
                    new ProfileRepository.ProfileDraft(
                            "profile_plan",
                            "Plan",
                            com.javaclaw.core.api.ProfileKind.PLAN,
                            "openai",
                            "gpt-5",
                            "Produce a concise actionable plan. Do not modify files.",
                            Set.of(),
                            SandboxMode.READ_ONLY,
                            8,
                            8,
                            java.util.Map.of()),
                    0,
                    "builtin-profile-plan");
        }
        ensureDefaultProfile(profiles, "profile_loop", "Loop", com.javaclaw.core.api.ProfileKind.LOOP, 25, 100);
        ensureDefaultProfile(
                profiles, "profile_workflow", "Workflow", com.javaclaw.core.api.ProfileKind.WORKFLOW, 32, 32);
        ensureDefaultProfile(profiles, "profile_sdd", "SDD", com.javaclaw.core.api.ProfileKind.SDD, 32, 32);
        ensureDefaultProfile(
                profiles, "profile_schedule", "Schedule", com.javaclaw.core.api.ProfileKind.SCHEDULE, 16, 16);
        if (profiles.list().stream().noneMatch(value -> "profile_subagent".equals(value.id()))) {
            profiles.put(
                    new ProfileRepository.ProfileDraft(
                            "profile_subagent",
                            "Subagent",
                            com.javaclaw.core.api.ProfileKind.SUBAGENT,
                            "openai",
                            "gpt-5",
                            "",
                            Set.of(),
                            SandboxMode.WORKSPACE_WRITE,
                            32,
                            32,
                            java.util.Map.of()),
                    0,
                    "builtin-profile-subagent");
        }
    }

    private static void ensureDefaultProfile(
            ProfileService profiles,
            String id,
            String name,
            com.javaclaw.core.api.ProfileKind kind,
            int iterations,
            int modelCalls) {
        if (profiles.list().stream().anyMatch(value -> id.equals(value.id()))) {
            return;
        }
        profiles.put(
                new ProfileRepository.ProfileDraft(
                        id,
                        name,
                        kind,
                        "openai",
                        "gpt-5",
                        "",
                        Set.of(),
                        SandboxMode.READ_ONLY,
                        iterations,
                        modelCalls,
                        java.util.Map.of()),
                0,
                "builtin-" + id);
    }

    /** 构建兼容测试与嵌入调用的最高权限边界；生产装配还会显式加入程序本地的配置根。 */
    public static SandboxPolicy hostCeiling(Path dataRoot) {
        return hostCeiling(dataRoot, dataRoot.resolveSibling("config-v4"));
    }

    /** 构建进程级最高权限边界并保护 H2 数据与凭据目录；缓存保持可供受控 Worker 和 worktree 使用。 */
    private static SandboxPolicy hostCeiling(Path dataRoot, Path configurationRoot) {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        java.util.LinkedHashSet<Path> protectedRoots = new java.util.LinkedHashSet<>();
        protectedRoots.add(dataRoot.toAbsolutePath().normalize());
        protectedRoots.add(configurationRoot.toAbsolutePath().normalize());
        protectedRoots.add(home.resolve(".javaclaw"));
        protectedRoots.add(home.resolve(".ssh"));
        protectedRoots.add(home.resolve(".gnupg"));
        protectedRoots.add(home.resolve(".aws"));
        protectedRoots.add(home.resolve(".azure"));
        protectedRoots.add(home.resolve(".kube"));
        protectedRoots.add(home.resolve("Library/Keychains"));
        return new SandboxPolicy(
                SandboxMode.HOST_FULL_ACCESS,
                Set.of(),
                Set.of(),
                Set.copyOf(protectedRoots),
                new NetworkPolicy(NetworkPolicy.Mode.FULL, Set.of()),
                Set.of(
                        "PATH",
                        "LANG",
                        "LC_ALL",
                        "TERM",
                        "HOME",
                        "TMPDIR",
                        "DISPLAY",
                        "XAUTHORITY",
                        "WAYLAND_DISPLAY",
                        "XDG_RUNTIME_DIR",
                        "PLAYWRIGHT_BROWSERS_PATH",
                        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"),
                Duration.ofMinutes(10),
                SandboxPolicy.DEFAULT_OUTPUT_LIMIT);
    }

    private static List<Path> sandboxModulePaths(String value) {
        java.util.ArrayList<Path> paths = new java.util.ArrayList<>();
        for (String entry : value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
            if (entry.isBlank()) {
                throw new IllegalArgumentException("JAVACLAW_SANDBOX_MODULE_PATH contains an empty path entry");
            }
            paths.add(Path.of(entry));
        }
        return List.copyOf(paths);
    }

    private static Path socketPath(String[] args) {
        for (int index = 0; index < args.length; index++) {
            if ("--socket".equals(args[index])) {
                if (index + 1 == args.length) {
                    throw new IllegalArgumentException("--socket requires a path");
                }
                return Path.of(args[index + 1]).toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String value : args) {
            if (flag.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static com.javaclaw.agent.prompt.AgentsInstructionSettings agentsInstructionSettings(
            com.javaclaw.server.configuration.ConfigurationUseCases configuration, ObjectMapper json) {
        var defaults = com.javaclaw.agent.prompt.AgentsInstructionSettings.defaults();
        var state = configuration.read();
        var values = state.values();
        return new com.javaclaw.agent.prompt.AgentsInstructionSettings(
                stringArraySetting(values.get("project_root_markers"), defaults.projectRootMarkers(), json),
                stringArraySetting(
                        values.get("project_doc_fallback_filenames"), defaults.projectDocFallbackFilenames(), json),
                integerSetting(values.get("project_doc_max_bytes"), defaults.projectDocMaxBytes(), json),
                state.revision());
    }

    private static List<String> stringArraySetting(String encoded, List<String> fallback, ObjectMapper json) {
        if (encoded == null) {
            return fallback;
        }
        try {
            var node = json.readTree(encoded);
            if (!node.isArray()) {
                throw new IllegalArgumentException("AGENTS.md list configuration must be a JSON array");
            }
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            node.forEach(value -> {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException("AGENTS.md list entries must be strings");
                }
                result.add(value.textValue());
            });
            return List.copyOf(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("cannot parse AGENTS.md list configuration", failure);
        }
    }

    private static int integerSetting(String encoded, int fallback, ObjectMapper json) {
        if (encoded == null) {
            return fallback;
        }
        try {
            var node = json.readTree(encoded);
            if (!node.canConvertToInt()) {
                throw new IllegalArgumentException("AGENTS.md byte budget must be an integer");
            }
            return node.intValue();
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("cannot parse AGENTS.md byte budget", failure);
        }
    }

    /** Returns an absolute, non-PATH Git command or no writable-Git capability. */
    private static List<String> controlledGitCommand() {
        String configured = System.getenv("JAVACLAW_GIT_EXECUTABLE");
        if (configured != null && !configured.isBlank()) {
            Path value = Path.of(configured).toAbsolutePath().normalize();
            if (java.nio.file.Files.isRegularFile(value) && java.nio.file.Files.isExecutable(value)) {
                return List.of(value.toString());
            }
            throw new IllegalStateException("JAVACLAW_GIT_EXECUTABLE is not an executable regular file");
        }
        List<Path> candidates = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? List.of(Path.of("C:/Program Files/Git/cmd/git.exe"), Path.of("C:/Program Files/Git/bin/git.exe"))
                : List.of(Path.of("/usr/bin/git"), Path.of("/bin/git"));
        return candidates.stream()
                .filter(java.nio.file.Files::isRegularFile)
                .filter(java.nio.file.Files::isExecutable)
                .findFirst()
                .map(value -> List.of(value.toAbsolutePath().normalize().toString()))
                .orElseGet(List::of);
    }

    private static List<String> browserServiceCommand(ObjectMapper json) {
        String encoded = System.getenv("JAVACLAW_BROWSER_SERVICE_COMMAND_JSON");
        if (encoded == null || encoded.isBlank()) {
            return packagedBrowserServiceCommand();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = json.readTree(encoded);
            if (!node.isArray() || node.isEmpty() || node.size() > 64) {
                throw new IllegalArgumentException("JAVACLAW_BROWSER_SERVICE_COMMAND_JSON must be an argv array");
            }
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            node.forEach(value -> {
                if (!value.isTextual()
                        || value.asText().isEmpty()
                        || value.asText().length() > 32_768) {
                    throw new IllegalArgumentException("browser argv contains an invalid value");
                }
                result.add(value.asText());
            });
            Path executable = Path.of(result.getFirst());
            if (!executable.isAbsolute()
                    || !java.nio.file.Files.isRegularFile(executable)
                    || !java.nio.file.Files.isExecutable(executable)) {
                throw new IllegalArgumentException("browser service executable must be an absolute executable file");
            }
            result.set(0, executable.toRealPath().toString());
            return List.copyOf(result);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("cannot parse JAVACLAW_BROWSER_SERVICE_COMMAND_JSON", failure);
        }
    }

    private static List<String> packagedBrowserServiceCommand() {
        String configuredLibrary = System.getenv("JAVACLAW_BROWSER_SERVICE_LIB");
        if (configuredLibrary == null || configuredLibrary.isBlank()) {
            return List.of();
        }
        try {
            Path library = Path.of(configuredLibrary).toRealPath();
            if (!java.nio.file.Files.isDirectory(library)) {
                return List.of();
            }
            boolean present =
                    java.nio.file.Files.isRegularFile(library.resolve("com.javaclaw.javaclaw-browser-service.jar"))
                            || java.nio.file.Files.isRegularFile(library.resolve("javaclaw-browser-service.jar"));
            if (!present) {
                return List.of();
            }
            Path javaExecutable = Path.of(
                            System.getProperty("java.home"),
                            "bin",
                            System.getProperty("os.name", "")
                                            .toLowerCase(java.util.Locale.ROOT)
                                            .contains("windows")
                                    ? "java.exe"
                                    : "java")
                    .toRealPath();
            if (!java.nio.file.Files.isExecutable(javaExecutable)) {
                return List.of();
            }
            return List.of(
                    javaExecutable.toString(),
                    "-cp",
                    library.resolve("*").toString(),
                    "com.javaclaw.browser.BrowserServiceMain");
        } catch (java.io.IOException | RuntimeException unavailable) {
            return List.of();
        }
    }

    private static Set<Path> browserInfrastructureRoots(List<String> command) {
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        Path executable = Path.of(command.getFirst());
        roots.add(executable.getParent());
        for (int index = 1; index < command.size(); index++) {
            String value = command.get(index);
            if (("-cp".equals(value) || "-classpath".equals(value) || "--class-path".equals(value))
                    && index + 1 < command.size()) {
                for (String entry :
                        command.get(++index).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    addInfrastructurePath(roots, entry);
                }
            } else {
                addInfrastructurePath(roots, value);
            }
        }
        String assets = System.getenv("JAVACLAW_BROWSER_ASSET_DIR");
        if (assets != null && !assets.isBlank()) {
            addInfrastructurePath(roots, assets);
        }
        return Set.copyOf(roots);
    }

    private static void addInfrastructurePath(Set<Path> roots, String value) {
        try {
            Path candidate = Path.of(value);
            if (candidate.getFileName() != null
                    && "*".equals(candidate.getFileName().toString())) {
                // Java classpath 的目录通配符由子 JVM 展开；沙箱仍需显式读取该库目录的权限。
                candidate = candidate.getParent();
            }
            if (candidate == null) {
                return;
            }
            if (!candidate.isAbsolute() || !java.nio.file.Files.exists(candidate)) {
                return;
            }
            Path real = candidate.toRealPath();
            roots.add(java.nio.file.Files.isDirectory(real) ? real : real.getParent());
        } catch (RuntimeException | java.io.IOException ignored) {
            // Non-path argv values carry no filesystem authority.
        }
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String configuredEmbeddingModel(ProviderService providers, String provider) {
        return providers.list().stream()
                .filter(value -> value.id().equalsIgnoreCase(provider))
                .map(com.javaclaw.server.model.ProviderState::embeddingModel)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("text-embedding-3-small");
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        persistence.close();
    }
}
