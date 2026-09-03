package com.javaclaw.server;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProfileBinding;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ProviderVerificationResult;
import com.javaclaw.api.ProviderVerificationState;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.extension.spi.EmbeddingBatch;
import com.javaclaw.extension.spi.EmbeddingPurpose;
import com.javaclaw.model.ProviderCredentialResolver;
import com.javaclaw.model.ProviderEmbeddingAdapterFactory;
import com.javaclaw.model.ProviderModelAdapterFactory;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.ProviderProfileRpcContracts;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.config.ProviderEmbeddingRegistry;
import com.javaclaw.server.config.ProviderModelRegistry;
import com.javaclaw.server.extension.BuiltinIsolatedServices;
import com.javaclaw.server.rpc.AppServerSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProviderAcceptanceTest {
    private static final String ENABLE_PROPERTY = "javaclaw.live.local";
    private static final String BASE_URI_PROPERTY = "javaclaw.live.base-uri";
    private static final String CHAT_MODEL_PROPERTY = "javaclaw.live.chat-model";
    private static final String EMBEDDING_MODEL_PROPERTY = "javaclaw.live.embedding-model";
    private static final String EMBEDDING_DIMENSIONS_PROPERTY = "javaclaw.live.embedding-dimensions";
    private static final String PROVIDER_ID = "live-local-provider";
    private static final String PROFILE_ID = "live-local-profile";
    private static final String MEMORY_PROPOSAL_ID = "live-memory-proposal";
    private static final String MEMORY_MARKER = "JAVACLAW_LIVE_MEMORY_ACCEPTANCE_20260903";
    private static final String SKILL_PROPOSAL_ID = "live-skill-proposal";
    private static final String SKILL_ID = "live-review-skill";

    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledIfSystemProperty(named = ENABLE_PROPERTY, matches = "(?i:true)")
    void 本地兼容Provider贯通默认Profile对话Embedding记忆与Skill审核() throws Exception {
        LiveConfiguration configuration = LiveConfiguration.fromSystemProperties();
        Path workspaceRoot = temporaryDirectory.resolve("workspace");
        Files.createDirectories(workspaceRoot);

        try (Boot boot = bootstrap()) {
            RpcClient rpc = new RpcClient(boot.components());
            rpc.initialize();
            Workspace workspace = rpc.createWorkspace(workspaceRoot);
            ConversationThread thread = rpc.createThread(workspace);
            ProviderEndpoint provider = rpc.createProvider(configuration);
            ProviderRef chat = new ProviderRef(provider.id(), provider.revision(), configuration.chatModel());
            ProviderRef embedding = new ProviderRef(provider.id(), provider.revision(), configuration.embeddingModel());

            ProviderVerificationResult chatVerification =
                    rpc.verifyProvider(chat, ProviderModelPurpose.CHAT, "live-chat-verify");
            ProviderVerificationResult embeddingVerification =
                    rpc.verifyProvider(embedding, ProviderModelPurpose.EMBEDDING, "live-embedding-verify");
            EmbeddingBinding embeddingBinding = rpc.bindEmbedding(embedding);
            AgentProfileRef profile = rpc.createProfile(chat);
            ProfileBinding profileBinding = rpc.bindDefaultProfile(workspace, profile);
            AgentTurn accepted = rpc.startTurn(thread);
            AgentTurn terminal = rpc.awaitTerminal(accepted.id());
            List<ItemEnvelope> items = rpc.listItems(thread);

            assertEquals(embedding, embeddingBinding.provider());
            assertEquals(ProviderVerificationState.SUCCEEDED, chatVerification.state(), chatVerification::toString);
            assertEquals(
                    ProviderVerificationState.SUCCEEDED,
                    embeddingVerification.state(),
                    embeddingVerification::toString);
            assertEquals(profile, profileBinding.profile());
            assertEquals(profile, accepted.profile());
            assertEquals(chat, accepted.provider());
            assertEquals(TurnStatus.COMPLETED, terminal.status(), terminal::toString);
            ItemEnvelope userItem = requireMessage(items, MessageRole.USER, MEMORY_MARKER);
            CorePayloads.Message assistant = lastMessage(items, MessageRole.ASSISTANT);
            assertFalse(assistant.text().isBlank(), "真实模型必须持久化非空 Assistant 消息");
            assertTrue(assistant.text().contains("LIVE_CHAT_OK"), assistant::text);

            verifyEmbedding(boot.embeddings(), configuration);
            verifyMemoryReview(rpc, workspace, thread, terminal, userItem);
            verifySkillReview(rpc, workspace, thread, terminal);
        }
    }

    private Boot bootstrap() {
        AppServerBootstrap.Foundation foundation = PlatformFoundationFactory.create(
                temporaryDirectory.resolve("data-v5"),
                Clock.systemUTC(),
                new LockedMasterKeyProtector(),
                required -> {});
        ProviderCredentialResolver credentials = ignored -> Optional.empty();
        ProviderModelAdapterFactory modelAdapters = new ProviderModelAdapterFactory(credentials);
        ProviderEmbeddingAdapterFactory embeddingAdapters = new ProviderEmbeddingAdapterFactory(credentials);
        try (StartupCloseStack startup = new StartupCloseStack()) {
            AppServerBootstrap.ownFoundation(startup, foundation);
            ProviderModelRegistry models = startup.own(new ProviderModelRegistry(
                    foundation.providers(),
                    modelAdapters::create,
                    foundation.vault().runtimeGate()));
            ProviderEmbeddingRegistry embeddings = startup.own(new ProviderEmbeddingRegistry(
                    foundation.providers(),
                    foundation.embeddingBinding(),
                    embeddingAdapters::create,
                    foundation.vault().runtimeGate()));
            foundation.vault().onRuntimeChange(models::invalidate, models::reload);
            foundation.vault().onRuntimeChange(embeddings::invalidate, embeddings::reload);
            AppServerBootstrap.Components components = AppServerBootstrap.createReal(
                    foundation,
                    new AppServerRuntimeBootstrap.RuntimeDependencies(
                            models,
                            embeddings,
                            embeddingAdapters::create,
                            AppServerBootstrap.modelDiscovery(foundation, credentials),
                            BuiltinIsolatedServices.browserUnavailable(),
                            AppServerBootstrap.productionMcpPorts(foundation)),
                    startup);
            startup.releaseAll();
            return new Boot(components, embeddings);
        }
    }

    private void verifyEmbedding(ProviderEmbeddingRegistry embeddings, LiveConfiguration configuration)
            throws Exception {
        EmbeddingBatch result = embeddings.embed(
                List.of("JavaClaw live embedding acceptance"), EmbeddingPurpose.QUERY, new CancellationSource());

        assertEquals(configuration.embeddingDimensions(), result.dimensions());
        assertEquals(1, result.vectors().size());
        assertEquals(
                configuration.embeddingDimensions(),
                result.vectors().getFirst().values().size());
        assertEquals(64, result.fingerprint().length());
    }

    private void verifyMemoryReview(
            RpcClient rpc, Workspace workspace, ConversationThread thread, AgentTurn turn, ItemEnvelope userItem) {
        ExtensionScope scope = ExtensionScope.turn(workspace, thread, turn);
        MemoryContracts.Source source =
                new MemoryContracts.Source(workspace.id(), thread.id(), userItem.id(), MEMORY_MARKER);
        MemoryContracts.LearningProposalRequest request = new MemoryContracts.LearningProposalRequest(
                MEMORY_PROPOSAL_ID,
                MemoryContracts.MemoryKind.FACT,
                "workspace",
                MEMORY_MARKER,
                Set.of("live", "acceptance"),
                source);
        MemoryContracts.LearningResult submitted = rpc.extensionCommand(
                scope,
                BuiltinExtensionIds.MEMORY,
                "proposal/submit",
                "live-memory-submit",
                0,
                request,
                MemoryContracts.LearningResult.class);

        assertEquals(MemoryContracts.LearningAction.PROPOSED, submitted.action());
        assertEquals(
                MemoryContracts.ProposalState.PENDING,
                submitted.proposal().orElseThrow().state());
        MemoryContracts.Proposal accepted = rpc.extensionCommand(
                scope,
                BuiltinExtensionIds.MEMORY,
                "proposal/accept",
                "live-memory-accept",
                1,
                new MemoryContracts.ProposalDecision(MEMORY_PROPOSAL_ID),
                MemoryContracts.Proposal.class);
        assertEquals(MemoryContracts.ProposalState.ACCEPTED, accepted.state());

        MemoryContracts.SearchResult found = rpc.extensionQuery(
                workspace,
                Optional.empty(),
                Optional.empty(),
                BuiltinExtensionIds.MEMORY,
                "search",
                new MemoryContracts.SearchRequest(MEMORY_MARKER, Set.of("workspace"), Set.of("live", "acceptance"), 10),
                MemoryContracts.SearchResult.class);
        assertEquals(
                List.of(accepted.memoryId().orElseThrow()),
                found.matches().stream().map(MemoryContracts.Memory::id).toList());
    }

    private void verifySkillReview(RpcClient rpc, Workspace workspace, ConversationThread thread, AgentTurn turn) {
        ExtensionScope scope = ExtensionScope.turn(workspace, thread, turn);
        SkillContracts.ProposeRequest candidate = new SkillContracts.ProposeRequest(
                SKILL_PROPOSAL_ID, SKILL_ID, "Live Review Skill", "验证人工审核后的 Skill 可发现", "只返回已经核验的事实。");
        SkillContracts.Proposal submitted = rpc.extensionCommand(
                scope,
                BuiltinExtensionIds.SKILL,
                "proposal/submit",
                "live-skill-submit",
                0,
                candidate,
                SkillContracts.Proposal.class);
        assertEquals(SkillContracts.ProposalState.PENDING, submitted.state());

        SkillContracts.Proposal adopted = rpc.extensionCommand(
                scope,
                BuiltinExtensionIds.SKILL,
                "proposal/adopt",
                "live-skill-adopt",
                1,
                new SkillContracts.ProposalDecision(SKILL_PROPOSAL_ID),
                SkillContracts.Proposal.class);
        assertEquals(SkillContracts.ProposalState.ADOPTED_AS_DRAFT, adopted.state());
        assertEquals(SKILL_ID, adopted.draftId().orElseThrow());

        SkillContracts.PublishedSkill published = rpc.extensionCommand(
                scope,
                BuiltinExtensionIds.SKILL,
                "publish",
                "live-skill-publish",
                0,
                new SkillContracts.PublishRequest(SKILL_ID, 1),
                SkillContracts.PublishedSkill.class);
        assertFalse(published.enabled());
        SkillContracts.PublishedSkill enabled = rpc.extensionCommand(
                scope,
                BuiltinExtensionIds.SKILL,
                "enable",
                "live-skill-enable",
                published.revision(),
                new SkillContracts.EnableRequest(SKILL_ID, true),
                SkillContracts.PublishedSkill.class);
        assertTrue(enabled.enabled());

        verifyFrozenSkill(rpc, workspace, thread, turn, enabled);
    }

    private void verifyFrozenSkill(
            RpcClient rpc,
            Workspace workspace,
            ConversationThread thread,
            AgentTurn turn,
            SkillContracts.PublishedSkill enabled) {
        SkillContracts.SearchResult catalog = rpc.extensionQuery(
                workspace,
                Optional.of(thread.id()),
                Optional.of(turn.id()),
                BuiltinExtensionIds.SKILL,
                "search",
                new SkillContracts.SearchRequest("Live Review", 10),
                SkillContracts.SearchResult.class);
        assertFalse(catalog.catalogDigest().isBlank());
        SkillContracts.Summary summary = catalog.matches().stream()
                .filter(value -> SKILL_ID.equals(value.id()))
                .findFirst()
                .orElseThrow();
        SkillContracts.PublishedSkill frozen = rpc.extensionQuery(
                workspace,
                Optional.of(thread.id()),
                Optional.of(turn.id()),
                BuiltinExtensionIds.SKILL,
                "published/read",
                new SkillContracts.PublishedReadRequest(summary.id(), summary.revision(), summary.digest()),
                SkillContracts.PublishedSkill.class);
        assertEquals(enabled, frozen);
    }

    private static ItemEnvelope requireMessage(List<ItemEnvelope> items, MessageRole role, String text) {
        return items.stream()
                .filter(item -> CoreSchemas.MESSAGE.equals(item.schemaId()))
                .filter(item -> {
                    CorePayloads.Message message = JSON.decode(item.payload(), CorePayloads.Message.class);
                    return message.role() == role && message.text().contains(text);
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有找到预期的 " + role + " 消息"));
    }

    private static CorePayloads.Message lastMessage(List<ItemEnvelope> items, MessageRole role) {
        return items.stream()
                .filter(item -> CoreSchemas.MESSAGE.equals(item.schemaId()))
                .map(item -> JSON.decode(item.payload(), CorePayloads.Message.class))
                .filter(message -> message.role() == role)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("没有找到 " + role + " 消息"));
    }

    private static final com.javaclaw.protocol.CanonicalJson JSON = new com.javaclaw.protocol.CanonicalJson();

    private record Boot(AppServerBootstrap.Components components, ProviderEmbeddingRegistry embeddings)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            components.close();
        }
    }

    private record ExtensionScope(Workspace workspace, ThreadId threadId, TurnId turnId) {
        private static ExtensionScope turn(Workspace workspace, ConversationThread thread, AgentTurn turn) {
            return new ExtensionScope(workspace, thread.id(), turn.id());
        }
    }

    private record LiveConfiguration(URI baseUri, String chatModel, String embeddingModel, int embeddingDimensions) {
        private static LiveConfiguration fromSystemProperties() {
            return new LiveConfiguration(
                    openAiBaseUri(requiredProperty(BASE_URI_PROPERTY)),
                    requiredProperty(CHAT_MODEL_PROPERTY),
                    requiredProperty(EMBEDDING_MODEL_PROPERTY),
                    Integer.parseInt(requiredProperty(EMBEDDING_DIMENSIONS_PROPERTY)));
        }

        private static URI openAiBaseUri(String value) {
            URI configured = URI.create(value);
            String path = Optional.ofNullable(configured.getPath()).orElse("");
            if (!path.isEmpty() && !"/".equals(path)) {
                return configured;
            }
            String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
            return URI.create(normalized + "/v1");
        }

        private static String requiredProperty(String name) {
            String value = System.getProperty(name, "").strip();
            if (value.isEmpty()) {
                throw new IllegalStateException("启用 live 测试时必须提供 -D" + name);
            }
            return value;
        }
    }

    private final class RpcClient {
        private final AppServerBootstrap.Components components;
        private final AppServerSession session;
        private final AtomicInteger requestIds = new AtomicInteger();

        private RpcClient(AppServerBootstrap.Components components) {
            this.components = components;
            session = components.newSession();
        }

        private void initialize() {
            InitializeParams params = new InitializeParams(
                    ProtocolVersion.CURRENT,
                    new ClientInfo("local-provider-acceptance", "5.0"),
                    new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
            assertTrue(call("initialize/session", params).result().isPresent());
        }

        private Workspace createWorkspace(Path root) {
            return write(
                    "workspace/create",
                    "live-workspace-create",
                    0,
                    new CoreRpcContracts.WorkspaceCreatePayload("Local Provider Acceptance", root),
                    Workspace.class);
        }

        private ConversationThread createThread(Workspace workspace) {
            CoreRpcContracts.ThreadCreatePayload payload = new CoreRpcContracts.ThreadCreatePayload(
                    workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "Local Provider Acceptance");
            return write("thread/create", "live-thread-create", 0, payload, ConversationThread.class);
        }

        private ProviderEndpoint createProvider(LiveConfiguration configuration) {
            ProviderModelSpec chat = new ProviderModelSpec(
                    configuration.chatModel(),
                    configuration.chatModel(),
                    Set.of(ProviderModelPurpose.CHAT),
                    OptionalInt.empty());
            ProviderModelSpec embedding = new ProviderModelSpec(
                    configuration.embeddingModel(),
                    configuration.embeddingModel(),
                    Set.of(ProviderModelPurpose.EMBEDDING),
                    OptionalInt.of(configuration.embeddingDimensions()));
            ProviderEndpointSpec spec = new ProviderEndpointSpec(
                    "Local OpenAI-compatible Provider",
                    ProviderAdapter.OPENAI_COMPATIBLE,
                    Optional.of(configuration.baseUri()),
                    ProviderAuthentication.NONE,
                    List.of(chat, embedding),
                    Optional.empty(),
                    Duration.ofSeconds(120),
                    0,
                    ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
            return write(
                    "provider/create",
                    "live-provider-create",
                    0,
                    new ProviderProfileRpcContracts.ProviderCreatePayload(PROVIDER_ID, spec, ProviderLifecycle.ACTIVE),
                    ProviderEndpoint.class);
        }

        private EmbeddingBinding bindEmbedding(ProviderRef embedding) {
            return write(
                    "provider/embeddingBinding/update",
                    "live-embedding-bind",
                    0,
                    new ProviderProfileRpcContracts.EmbeddingBindingUpdatePayload(embedding),
                    EmbeddingBinding.class);
        }

        private ProviderVerificationResult verifyProvider(
                ProviderRef provider, ProviderModelPurpose purpose, String key) {
            ProviderVerificationRpcContracts.VerifyPayload payload = new ProviderVerificationRpcContracts.VerifyPayload(
                    provider, purpose, true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
            return write(
                    ProviderVerificationRpcContracts.METHOD,
                    key,
                    provider.endpointRevision(),
                    payload,
                    ProviderVerificationResult.class);
        }

        private AgentProfileRef createProfile(ProviderRef chat) {
            AgentProfileSpec spec = new AgentProfileSpec(
                    "Local Acceptance Agent",
                    "直接、简短地回答用户，不要省略最终答案。",
                    chat,
                    new PermissionProfileRef("standard", 1),
                    Set.of(),
                    new TurnBudget(32_000, 2_048, 4, 0, Duration.ofSeconds(120)));
            AgentProfile profile = write(
                    "profile/create",
                    "live-profile-create",
                    0,
                    new ProviderProfileRpcContracts.AgentProfileCreatePayload(PROFILE_ID, spec),
                    AgentProfile.class);
            return new AgentProfileRef(profile.id(), profile.revision());
        }

        private ProfileBinding bindDefaultProfile(Workspace workspace, AgentProfileRef profile) {
            ProviderProfileRpcContracts.ProfileBindingUpdatePayload payload =
                    new ProviderProfileRpcContracts.ProfileBindingUpdatePayload(
                            workspace.id(), Optional.empty(), profile);
            return write("profile/binding/update", "live-profile-bind", 0, payload, ProfileBinding.class);
        }

        private AgentTurn startTurn(ConversationThread thread) {
            CoreRpcContracts.TurnStartPayload payload = new CoreRpcContracts.TurnStartPayload(
                    thread.id(),
                    Optional.empty(),
                    "请只用一句话确认本地模型对话成功，并在答案中包含 LIVE_CHAT_OK。" + " 这条消息也包含记忆验收标记：" + MEMORY_MARKER);
            return write("turn/start", "live-turn-start", 0, payload, AgentTurn.class);
        }

        private AgentTurn awaitTerminal(TurnId turnId) throws InterruptedException {
            AgentTurn last = null;
            for (int attempt = 0; attempt < 1_800; attempt++) {
                last = decode(call("turn/read", new CoreRpcContracts.TurnQuery(turnId)), AgentTurn.class);
                if (last.status() == TurnStatus.COMPLETED
                        || last.status() == TurnStatus.FAILED
                        || last.status() == TurnStatus.CANCELLED) {
                    return last;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("真实模型 Turn 未在 180 秒内结束: " + last);
        }

        private List<ItemEnvelope> listItems(ConversationThread thread) {
            CoreRpcContracts.ItemListResult result = decode(
                    call("item/list", new CoreRpcContracts.ItemList(thread.id(), 0, 100)),
                    CoreRpcContracts.ItemListResult.class);
            return result.items();
        }

        private <T> T extensionCommand(
                ExtensionScope scope,
                String extensionId,
                String operation,
                String key,
                long expectedRevision,
                Object input,
                Class<T> type) {
            ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                    extensionId,
                    scope.workspace().id(),
                    Optional.of(scope.threadId()),
                    Optional.of(scope.turnId()),
                    operation,
                    components.json().encode(input));
            ExtensionRpcContracts.CallResult result =
                    write("extension/command", key, expectedRevision, call, ExtensionRpcContracts.CallResult.class);
            return components.json().decode(result.payload(), type);
        }

        private <T> T extensionQuery(
                Workspace workspace,
                Optional<ThreadId> threadId,
                Optional<TurnId> turnId,
                String extensionId,
                String operation,
                Object input,
                Class<T> type) {
            ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                    extensionId,
                    workspace.id(),
                    threadId,
                    turnId,
                    operation,
                    components.json().encode(input));
            ExtensionRpcContracts.CallResult result =
                    decode(call("extension/query", call), ExtensionRpcContracts.CallResult.class);
            return components.json().decode(result.payload(), type);
        }

        private <T> T write(String method, String key, long revision, Object payload, Class<T> type) {
            WriteCommand command =
                    new WriteCommand(key, revision, components.json().encode(payload));
            return decode(call(method, command), type);
        }

        private JsonRpcResponse call(String method, Object params) {
            String id = "live-request-" + requestIds.incrementAndGet();
            return session.handle(
                    new JsonRpcRequest(new RpcId(id), method, components.json().encode(params)));
        }

        private <T> T decode(JsonRpcResponse response, Class<T> type) {
            return components
                    .json()
                    .decode(
                            response.result()
                                    .orElseThrow(() ->
                                            new AssertionError(response.error().orElseThrow())),
                            type);
        }
    }
}
