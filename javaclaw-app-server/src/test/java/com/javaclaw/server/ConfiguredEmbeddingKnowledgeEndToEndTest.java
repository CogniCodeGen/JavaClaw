package com.javaclaw.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CoreTools;
import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionState;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ToolIdentity;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.extension.spi.ExtensionExecutionReceipt;
import com.javaclaw.extension.spi.IsolatedServiceInvocation;
import com.javaclaw.extension.spi.IsolatedServicePort;
import com.javaclaw.model.ProviderCredentialResolver;
import com.javaclaw.model.ProviderEmbeddingAdapterFactory;
import com.javaclaw.protocol.AgentRoleRpcContracts;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.InputJobRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.PermissionProfileRpcContracts;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.ProviderRpcContracts;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelMessage;
import com.javaclaw.runtime.ModelToolCall;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.config.ProviderEmbeddingRegistry;
import com.javaclaw.server.rpc.AppServerSession;
import com.javaclaw.server.testkit.AttachmentRpcTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredEmbeddingKnowledgeEndToEndTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T08:00:00Z"), ZoneOffset.UTC);
    private static final String PROVIDER_ID = "knowledge-provider";
    private static final String MODEL_ID = "local-embedding-model";
    private static final String PERMISSION_ID = "knowledge-reader";
    private static final String ROLE_ID = "knowledge-agent";
    private static final String SOURCE_ID = "architecture-guide";
    private static final String KNOWLEDGE_SEARCH = "knowledge_search";

    @TempDir
    Path temporaryDirectory;

    @Test
    void 精确Embedding绑定通过真实Adapter建立Hybrid索引并供Agent检索() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("workspace"));
        KnowledgeAgentModel model = new KnowledgeAgentModel();
        ExtractionService extraction = new ExtractionService();
        try (EmbeddingHttpServer endpoint = EmbeddingHttpServer.start();
                Boot boot = bootstrap(model, extraction)) {
            RpcClient rpc = new RpcClient(boot.components());
            rpc.initialize();
            Workspace workspace = rpc.createWorkspace(temporaryDirectory.resolve("workspace"));
            ConversationThread thread = rpc.createThread(workspace);
            ProviderRef provider = rpc.createProvider(endpoint.baseUri());
            EmbeddingBinding binding = rpc.bindEmbedding(provider);
            AgentRoleRef role = rpc.createRole(installPermission(rpc), provider);
            AttachmentRef attachment = rpc.upload(workspace, "architecture source");

            KnowledgeContracts.ImportAccepted accepted = rpc.importKnowledge(workspace, attachment);
            rpc.awaitJob(accepted.jobId(), ExecutionState.COMPLETED);
            KnowledgeContracts.Source source = rpc.readSource(workspace);
            KnowledgeContracts.Generation generation = rpc.readGeneration(workspace, source.activeGenerationId());

            assertEquals(provider, binding.provider());
            assertEquals(
                    provider,
                    boot.foundation().embeddingBinding().find().orElseThrow().provider());
            assertEquals(KnowledgeContracts.RetrievalMode.HYBRID, generation.retrievalMode());
            assertEquals(3, generation.embeddingDimensions());
            assertEquals(64, generation.embeddingFingerprint().orElseThrow().length());
            assertEquals(1, endpoint.requests().size());
            assertEquals(1, extraction.invocations.get());

            AgentTurn started = rpc.startTurn(thread);
            assertEquals(role, started.role());
            AgentTurn terminal = rpc.awaitTerminal(started.id());

            assertEquals(TurnStatus.COMPLETED, terminal.status());
            assertTrue(model.visibleTools.size() >= 2);
            assertTrue(model.visibleTools.get(1).contains(KNOWLEDGE_SEARCH));
            assertTrue(model.toolOutputs.stream().anyMatch(output -> output.contains(SOURCE_ID)));
            assertTrue(model.toolOutputs.stream().anyMatch(output -> output.contains("HYBRID")));
            assertEquals(2, endpoint.requests().size());
            endpoint.requests().forEach(this::assertEmbeddingRequest);
        }
    }

    private Boot bootstrap(ModelGateway model, IsolatedServicePort extraction) {
        AppServerBootstrap.Foundation foundation = PlatformFoundationFactory.create(
                temporaryDirectory.resolve("data-v6"), CLOCK, new LockedMasterKeyProtector(), required -> {});
        ProviderCredentialResolver credentials = ignored -> Optional.empty();
        ProviderEmbeddingAdapterFactory adapters = new ProviderEmbeddingAdapterFactory(credentials);
        try (StartupCloseStack startup = new StartupCloseStack()) {
            AppServerBootstrap.ownFoundation(startup, foundation);
            ProviderEmbeddingRegistry embeddings = startup.own(new ProviderEmbeddingRegistry(
                    foundation.providers(),
                    foundation.embeddingBinding(),
                    adapters::create,
                    foundation.vault().runtimeGate()));
            foundation.vault().onRuntimeChange(embeddings::invalidate, embeddings::reload);
            AppServerBootstrap.Components components = AppServerBootstrap.createReal(
                    foundation,
                    new AppServerRuntimeBootstrap.RuntimeDependencies(
                            model,
                            embeddings,
                            adapters::create,
                            AppServerBootstrap.modelDiscovery(foundation, credentials),
                            extraction,
                            AppServerBootstrap.productionMcpPorts(foundation)),
                    startup);
            startup.releaseAll();
            return new Boot(foundation, components);
        }
    }

    private PermissionProfileRef installPermission(RpcClient rpc) {
        PermissionProfileRpcContracts.ClonePayload clone =
                new PermissionProfileRpcContracts.ClonePayload(new PermissionProfileRef("standard", 1), PERMISSION_ID);
        rpc.write("permissionProfile/clone", "permission-clone", 0, clone, PermissionProfile.class);
        PermissionProfile profile = new PermissionProfile(
                PERMISSION_ID,
                2,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(), Set.of(), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(1)),
                new ToolPermission(
                        Set.of("tool_search", KNOWLEDGE_SEARCH), ToolRisk.READ_ONLY, ApprovalRequirement.NONE),
                new ResourceLimits(64L * 1024 * 1024, 4L * 1024 * 1024, 1, 16));
        rpc.write(
                "permissionProfile/update",
                "permission-update",
                1,
                new PermissionProfileRpcContracts.UpdatePayload(profile),
                PermissionProfile.class);
        return new PermissionProfileRef(PERMISSION_ID, 2);
    }

    private void assertEmbeddingRequest(EmbeddingRequest request) {
        assertEquals("POST", request.method());
        assertEquals("/v1/embeddings", request.path());
        assertTrue(request.authorization().isEmpty());
        assertTrue(request.body().contains("\"model\":\"" + MODEL_ID + "\""));
        assertTrue(request.body().contains("\"dimensions\":3"));
    }

    private static ProviderEndpointSpec providerSpec(URI baseUri) {
        ProviderModelSpec model = new ProviderModelSpec(
                MODEL_ID,
                "Local Embedding",
                Set.of(ProviderModelPurpose.CHAT, ProviderModelPurpose.EMBEDDING),
                OptionalInt.of(3));
        return new ProviderEndpointSpec(
                "Loopback Knowledge Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(baseUri),
                ProviderAuthentication.NONE,
                List.of(model),
                Optional.empty(),
                Duration.ofSeconds(5),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    private record Boot(AppServerBootstrap.Foundation foundation, AppServerBootstrap.Components components)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            components.close();
        }
    }

    private final class RpcClient {
        private final AppServerBootstrap.Components components;
        private final AppServerSession session;
        private final AtomicInteger requestIds = new AtomicInteger();
        private ExecutionOverrides execution = ExecutionOverrides.empty();

        private RpcClient(AppServerBootstrap.Components components) {
            this.components = components;
            session = components.newSession();
        }

        private void initialize() {
            InitializeParams params = new InitializeParams(
                    ProtocolVersion.CURRENT,
                    new ClientInfo("embedding-knowledge-e2e", "6.0"),
                    new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
            assertTrue(call("initialize/session", params).result().isPresent());
        }

        private Workspace createWorkspace(Path root) {
            return write(
                    "workspace/create",
                    "workspace-create",
                    0,
                    new CoreRpcContracts.WorkspaceCreatePayload("Embedding Knowledge", root),
                    Workspace.class);
        }

        private ConversationThread createThread(Workspace workspace) {
            CoreRpcContracts.ThreadCreatePayload payload = new CoreRpcContracts.ThreadCreatePayload(
                    workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "Knowledge E2E");
            return write("thread/create", "thread-create", 0, payload, ConversationThread.class);
        }

        private ProviderRef createProvider(URI baseUri) {
            ProviderRpcContracts.ProviderCreatePayload payload = new ProviderRpcContracts.ProviderCreatePayload(
                    PROVIDER_ID, providerSpec(baseUri), ProviderLifecycle.ACTIVE);
            ProviderEndpoint endpoint = write("provider/create", "provider-create", 0, payload, ProviderEndpoint.class);
            return new ProviderRef(endpoint.id(), endpoint.revision(), MODEL_ID);
        }

        private EmbeddingBinding bindEmbedding(ProviderRef provider) {
            return write(
                    "provider/embeddingBinding/update",
                    "embedding-bind",
                    0,
                    new ProviderRpcContracts.EmbeddingBindingUpdatePayload(provider),
                    EmbeddingBinding.class);
        }

        private AgentRoleRef createRole(PermissionProfileRef permission, ProviderRef provider) {
            AgentRoleSpec spec = new AgentRoleSpec(
                    "Knowledge Agent",
                    "",
                    "只使用已授权工具检索知识。",
                    Optional.empty(),
                    Optional.empty(),
                    CapabilityNarrowing.inherit(),
                    PermissionConstraint.INHERIT,
                    java.util.Map.of());
            AgentRole role = write(
                    "agent/role/create",
                    "role-create",
                    0,
                    new AgentRoleRpcContracts.CreatePayload(ROLE_ID, spec),
                    AgentRole.class);
            execution = new ExecutionOverrides(
                    Optional.of(role.ref()),
                    Optional.of(provider),
                    Optional.of(permission),
                    Optional.of(ApprovalPolicy.NONE),
                    Optional.of(new TurnBudget(4_000, 1_000, 3, 0, Duration.ofSeconds(30))),
                    Optional.of(Set.of("tool_search", KNOWLEDGE_SEARCH)),
                    Optional.empty());
            return role.ref();
        }

        private AttachmentRef upload(Workspace workspace, String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            AttachmentMetadata metadata = AttachmentRpcTestClient.upload(
                    session,
                    components.json(),
                    "knowledge-attachment",
                    AttachmentScope.workspace(workspace.id()),
                    "text/plain",
                    bytes);
            return new AttachmentRef(metadata.digest(), metadata.mediaType(), "architecture.txt", metadata.sizeBytes());
        }

        private KnowledgeContracts.ImportAccepted importKnowledge(Workspace workspace, AttachmentRef attachment) {
            KnowledgeContracts.ImportRequest request = new KnowledgeContracts.ImportRequest(
                    SOURCE_ID,
                    "Boundary Notes",
                    attachment,
                    10_000,
                    500,
                    50,
                    KnowledgeContracts.RetrievalPreference.EMBEDDING_PREFERRED);
            ExtensionRpcContracts.CallPayload call =
                    extensionCall(workspace, "source/import", components.json().encode(request));
            ExtensionRpcContracts.CallResult result =
                    write("extension/command", "knowledge-import", 0, call, ExtensionRpcContracts.CallResult.class);
            return components.json().decode(result.payload(), KnowledgeContracts.ImportAccepted.class);
        }

        private KnowledgeContracts.Source readSource(Workspace workspace) {
            return query(
                    workspace, "source/read", new KnowledgeContracts.Key(SOURCE_ID), KnowledgeContracts.Source.class);
        }

        private KnowledgeContracts.Generation readGeneration(Workspace workspace, String generationId) {
            return query(
                    workspace,
                    "generation/read",
                    new KnowledgeContracts.Key(generationId),
                    KnowledgeContracts.Generation.class);
        }

        private <T> T query(Workspace workspace, String operation, Object input, Class<T> type) {
            ExtensionRpcContracts.CallPayload payload =
                    extensionCall(workspace, operation, components.json().encode(input));
            ExtensionRpcContracts.CallResult result =
                    decode(call("extension/query", payload), ExtensionRpcContracts.CallResult.class);
            return components.json().decode(result.payload(), type);
        }

        private ExtensionRpcContracts.CallPayload extensionCall(
                Workspace workspace, String operation, CanonicalPayload payload) {
            return new ExtensionRpcContracts.CallPayload(
                    BuiltinExtensionIds.KNOWLEDGE,
                    workspace.id(),
                    Optional.empty(),
                    Optional.empty(),
                    operation,
                    payload);
        }

        private ExtensionExecutionReceipt awaitJob(String jobId, ExecutionState expected) throws InterruptedException {
            for (int attempt = 0; attempt < 500; attempt++) {
                InputJobRpcContracts.JobReadResult result = decode(
                        call("extension/job/read", new InputJobRpcContracts.JobReadPayload(jobId)),
                        InputJobRpcContracts.JobReadResult.class);
                if (result.job().state() == expected) {
                    return result.job();
                }
                if (result.job().state() == ExecutionState.FAILED) {
                    throw new AssertionError("Knowledge Job failed: " + result.job());
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Knowledge Job did not reach " + expected);
        }

        private AgentTurn startTurn(ConversationThread thread) {
            CoreRpcContracts.TurnStartPayload payload =
                    new CoreRpcContracts.TurnStartPayload(thread.id(), execution, "检索语义相关的架构资料", List.of());
            CoreRpcContracts.TurnStartResult result =
                    write("turn/start", "turn-start", 0, payload, CoreRpcContracts.TurnStartResult.class);
            assertEquals(result.turn().resolvedConfig(), result.configuration());
            return result.turn();
        }

        private AgentTurn awaitTerminal(TurnId turnId) throws InterruptedException {
            AgentTurn last = null;
            for (int attempt = 0; attempt < 500; attempt++) {
                last = decode(call("turn/read", new CoreRpcContracts.TurnQuery(turnId)), AgentTurn.class);
                if (last.status() == TurnStatus.COMPLETED || last.status() == TurnStatus.FAILED) {
                    return last;
                }
                Thread.sleep(10);
            }
            throw new AssertionError("Turn did not reach a terminal state: " + last);
        }

        private <T> T write(String method, String key, long revision, Object payload, Class<T> type) {
            WriteCommand command =
                    new WriteCommand(key, revision, components.json().encode(payload));
            return decode(call(method, command), type);
        }

        private JsonRpcResponse call(String method, Object params) {
            String id = "request-" + requestIds.incrementAndGet();
            return session.handle(
                    new JsonRpcRequest(new RpcId(id), method, components.json().encode(params)));
        }

        private <T> T decode(JsonRpcResponse response, Class<T> type) {
            CanonicalPayload payload = response.result()
                    .orElseThrow(() -> new AssertionError(response.error().orElseThrow()));
            return components.json().decode(payload, type);
        }
    }

    private static final class KnowledgeAgentModel implements ModelGateway {
        private final AtomicInteger invocations = new AtomicInteger();
        private final List<List<String>> visibleTools = new CopyOnWriteArrayList<>();
        private final List<String> toolOutputs = new CopyOnWriteArrayList<>();

        @Override
        public ModelCapabilities capabilities(String modelId) {
            if (!new ProviderRef(PROVIDER_ID, 1, MODEL_ID).routeKey().equals(modelId)) {
                throw new IllegalArgumentException("unknown model route");
            }
            return new ModelCapabilities(false, true, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation) {
            visibleTools.add(invocation.tools().stream()
                    .map(tool -> tool.identity().name())
                    .toList());
            invocation.messages().stream()
                    .filter(message -> message.role() == MessageRole.TOOL)
                    .map(ModelMessage::text)
                    .forEach(toolOutputs::add);
            return switch (invocations.incrementAndGet()) {
                case 1 ->
                    toolCall(new ModelToolCall(
                            "discover-knowledge",
                            CoreTools.search().identity(),
                            new CanonicalPayload("{\"limit\":10,\"query\":\"knowledge\"}")));
                case 2 ->
                    toolCall(new ModelToolCall(
                            "search-knowledge",
                            new ToolIdentity(BuiltinExtensionIds.KNOWLEDGE, KNOWLEDGE_SEARCH, 1),
                            new CanonicalPayload("{\"limit\":10,\"mediaTypes\":[],\"query\":\"semantic-neighbor\"}")));
                default -> complete();
            };
        }

        private static ModelInvocationResult toolCall(ModelToolCall call) {
            return new ModelInvocationResult(
                    "",
                    List.of(call),
                    new ModelUsage(10, 2, 0, 0),
                    Optional.empty(),
                    Optional.empty(),
                    ModelFinishReason.TOOL_CALLS);
        }

        private static ModelInvocationResult complete() {
            return new ModelInvocationResult(
                    "已检索知识",
                    List.of(),
                    new ModelUsage(10, 2, 0, 0),
                    Optional.empty(),
                    Optional.empty(),
                    ModelFinishReason.COMPLETE);
        }
    }

    private static final class ExtractionService implements IsolatedServicePort {
        private final AtomicInteger invocations = new AtomicInteger();
        private final com.javaclaw.protocol.CanonicalJson json = new com.javaclaw.protocol.CanonicalJson();

        @Override
        public CanonicalPayload invoke(IsolatedServiceInvocation invocation) {
            KnowledgeContracts.ExtractionRequest request =
                    json.decode(invocation.request(), KnowledgeContracts.ExtractionRequest.class);
            invocations.incrementAndGet();
            return json.encode(new KnowledgeContracts.ExtractionResult(
                    request.attachment().digest(), "fake-worker-v1", "模块之间通过明确边界协作，持久状态由事务存储统一管理。"));
        }
    }

    private record EmbeddingRequest(String method, String path, Optional<String> authorization, String body) {}

    private static final class EmbeddingHttpServer implements AutoCloseable {
        private static final byte[] RESPONSE = ("{\"data\":[{\"embedding\":[1.0,0.0,0.0],"
                        + "\"index\":0,\"object\":\"embedding\"}],\"model\":\""
                        + MODEL_ID
                        + "\",\"object\":\"list\","
                        + "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}")
                .getBytes(StandardCharsets.UTF_8);

        private final HttpServer server;
        private final ExecutorService executor;
        private final List<EmbeddingRequest> requests = new CopyOnWriteArrayList<>();

        private EmbeddingHttpServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        private static EmbeddingHttpServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "embedding-http-fixture");
                thread.setDaemon(true);
                return thread;
            });
            EmbeddingHttpServer fixture = new EmbeddingHttpServer(server, executor);
            server.createContext("/", fixture::handle);
            server.setExecutor(executor);
            server.start();
            return fixture;
        }

        private URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        }

        private List<EmbeddingRequest> requests() {
            return List.copyOf(requests);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new EmbeddingRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        Optional.ofNullable(exchange.getRequestHeaders().getFirst("Authorization")),
                        body));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, RESPONSE.length);
                exchange.getResponseBody().write(RESPONSE);
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
