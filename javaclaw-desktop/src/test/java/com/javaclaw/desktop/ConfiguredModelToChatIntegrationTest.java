package com.javaclaw.desktop;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ExecutionConfiguration;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ExecutionPreview;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ResolvedTurnConfigSummary;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.ThreadStatus;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InitializeResult;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.NegotiatedCapabilities;
import com.javaclaw.protocol.ProviderCredentialRpcContracts;
import com.javaclaw.protocol.ProviderRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredModelToChatIntegrationTest {
    private static final String MODEL = "configured-model";
    private static final String TEST_SECRET = "local-fake-service-key";
    private static final String MESSAGE = "请分析这个项目。\n先列出问题，再给出修改建议。";

    @Test
    void 从连接密钥模型配置到直接发送全链无需手动刷新且保持精确目标() throws Exception {
        try (var scenario = new Scenario();
                var presenter = scenario.presenter()) {
            var state = connect(presenter);
            ConversationThread target = scenario.fixture.server.thread();
            ExecutionConfiguration foreign = scenario.seedForeignThread();
            var initial = presenter
                    .submitSettingsRequest(client -> client.executions()
                            .preview(target.workspaceId(), Optional.of(target.id()), ExecutionOverrides.empty()))
                    .get(5, TimeUnit.SECONDS);
            assertFalse(initial.ready());

            ProviderEndpoint saved = presenter
                    .submitSettingsRequest(ConfiguredModelToChatIntegrationTest::configure)
                    .get(5, TimeUnit.SECONDS);
            ProviderRef exact = new ProviderRef(saved.id(), saved.revision(), MODEL);
            presenter
                    .useModel(Optional.of(target.workspaceId()), Optional.of(target.id()), exact)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            ExecutionPreview preview = presenter
                    .submitSettingsRequest(client -> client.executions()
                            .preview(target.workspaceId(), Optional.of(target.id()), ExecutionOverrides.empty()))
                    .get(5, TimeUnit.SECONDS);
            awaitReady(state);
            AgentTurn accepted =
                    presenter.send(MESSAGE, ExecutionOverrides.empty()).get(5, TimeUnit.SECONDS);

            assertTrue(preview.ready());
            assertEquals(Optional.of(exact), preview.provider());
            assertEquals(3, exact.endpointRevision());
            assertEquals(exact, accepted.provider());
            assertEquals(target.id(), accepted.threadId());
            assertEquals(TurnStatus.RUNNING, accepted.status());
            assertEquals(MESSAGE, scenario.sent.message());
            assertEquals(target.id(), scenario.sent.threadId());
            assertTrue(scenario.credentialVerified);
            assertTrue(scenario.fixture.defaults.isEmpty());
            assertEquals(
                    Optional.of(exact),
                    scenario.fixture.recent.orElseThrow().overrides().provider());
            assertEquals(
                    foreign,
                    scenario.fixture.configurations.get(foreign.threadId().orElseThrow()));
            assertEquals(
                    Set.of(target.id(), foreign.threadId().orElseThrow()), scenario.fixture.configurations.keySet());
            assertEquals(
                    List.of(
                            "provider/create",
                            "provider/credential/set",
                            "provider/update",
                            "thread/execution/update",
                            "execution/recent/update",
                            "turn/start"),
                    scenario.fixture.mutations);
        }
    }

    private static ProviderEndpoint configure(JavaClawClient client) {
        ProviderEndpoint created = client.providers()
                .create(
                        "configured",
                        spec(List.of(), Optional.empty()),
                        ProviderLifecycle.DISABLED,
                        CommandOptions.create(0));
        char[] secret = TEST_SECRET.toCharArray();
        ProviderCredentialBinding binding;
        try {
            binding = client.providers()
                    .setCredential(
                            created.id(), created.revision(), 0, secret, CommandOptions.create(created.revision()));
        } finally {
            Arrays.fill(secret, '\0');
        }
        ProviderEndpoint connected = binding.provider();
        return client.providers()
                .update(
                        connected.id(),
                        spec(
                                List.of(new ProviderModelSpec(
                                        MODEL, "常用模型", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                                connected.spec().credential()),
                        ProviderLifecycle.ACTIVE,
                        CommandOptions.create(connected.revision()));
    }

    private static ProviderEndpointSpec spec(List<ProviderModelSpec> models, Optional<CredentialRef> credential) {
        return new ProviderEndpointSpec(
                "测试兼容接口",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://127.0.0.1:11434/v1")),
                ProviderAuthentication.API_KEY,
                models,
                credential,
                Duration.ofSeconds(10),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    private static AtomicReference<DesktopState> connect(DesktopPresenter presenter) throws Exception {
        AtomicReference<DesktopState> state = new AtomicReference<>();
        presenter.subscribe(state::set);
        presenter.connect();
        awaitReady(state);
        return state;
    }

    private static void awaitReady(AtomicReference<DesktopState> state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((state.get().connection().status() != ConnectionState.Status.CONNECTED
                        || state.get().threads().selectedThread().isEmpty()
                        || state.get().interaction().busy())
                && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertEquals(ConnectionState.Status.CONNECTED, state.get().connection().status());
        assertTrue(state.get().threads().selectedThread().isPresent());
        assertFalse(state.get().interaction().busy());
    }

    /** 假服务保留真实 SDK 与会话 Secret 编解码；Turn 接受结果完全在本地生成，不调用模型。 */
    private static final class Scenario implements AutoCloseable {
        private final ModelSelectionRpcFixture fixture = new ModelSelectionRpcFixture();
        private final SessionSecretChannel secrets = SessionSecretChannel.open();
        private final Map<Long, ProviderEndpoint> providers = new HashMap<>();
        private final Function<JsonRpcRequest, Optional<Object>> executionRequests = fixture.server.requestOverride;
        private ProviderEndpoint latest;
        private CoreRpcContracts.TurnStartPayload sent;
        private AgentTurn started;
        private boolean credentialVerified;

        private Scenario() {
            fixture.server.requestOverride = this::respond;
        }

        private DesktopPresenter presenter() {
            return new DesktopPresenter(
                    fixture.server::client, Runnable::run, Clock.fixed(DesktopTestFixtures.NOW, ZoneOffset.UTC));
        }

        private Optional<Object> respond(JsonRpcRequest request) {
            return switch (request.method()) {
                case "initialize/session" ->
                    Optional.of(new InitializeResult(
                            3,
                            "local-fake-service",
                            "6.0",
                            new NegotiatedCapabilities(Set.of("core.item-envelope"), Set.of()),
                            secrets.publicKey()));
                case "provider/create" -> Optional.of(createProvider(request));
                case "provider/credential/set" -> Optional.of(bindCredential(request));
                case "provider/update" -> Optional.of(updateProvider(request));
                case "provider/list" -> Optional.of(configuredProviders());
                case "provider/read" ->
                    Optional.of(providers.get(fixture.json
                            .decode(request.params(), ProviderRpcContracts.ProviderReadPayload.class)
                            .revision()));
                case "execution/preview" -> checkedPreview(request);
                case "turn/start" -> Optional.of(start(request));
                case "turn/read" -> started == null ? Optional.empty() : Optional.of(completed());
                case "item/list" -> Optional.of(new CoreRpcContracts.ItemListResult(List.of(), 0));
                default -> executionRequests.apply(request);
            };
        }

        private ProviderRpcContracts.ProviderListResult configuredProviders() {
            return new ProviderRpcContracts.ProviderListResult(latest == null ? List.of() : List.of(latest));
        }

        private Optional<Object> checkedPreview(JsonRpcRequest request) {
            Optional<Object> result = executionRequests.apply(request);
            ExecutionPreview preview = (ExecutionPreview) result.orElseThrow();
            preview.provider().ifPresent(this::requireAvailable);
            return result;
        }

        private ProviderEndpoint createProvider(JsonRpcRequest request) {
            WriteCommand command = fixture.json.decode(request.params(), WriteCommand.class);
            var payload = fixture.json.decode(command.payload(), ProviderRpcContracts.ProviderCreatePayload.class);
            assertEquals(0, command.expectedRevision());
            assertEquals(ProviderLifecycle.DISABLED, payload.lifecycle());
            assertTrue(payload.spec().models().isEmpty());
            return save(request.method(), payload.id(), 1, payload.spec(), payload.lifecycle());
        }

        private ProviderCredentialBinding bindCredential(JsonRpcRequest request) {
            WriteCommand command = fixture.json.decode(request.params(), WriteCommand.class);
            var payload = fixture.json.decode(command.payload(), ProviderCredentialRpcContracts.SetPayload.class);
            assertEquals(latest.revision(), command.expectedRevision());
            assertFalse(request.params().json().contains(TEST_SECRET));
            byte[] plaintext = secrets.unseal(payload.secret(), ProviderCredentialRpcContracts.SET_PURPOSE);
            try {
                assertArrayEquals(TEST_SECRET.getBytes(StandardCharsets.UTF_8), plaintext);
                credentialVerified = true;
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
            CredentialRef credential = new CredentialRef("provider", "configured-test-key");
            ProviderEndpoint saved = save(
                    request.method(),
                    latest.id(),
                    latest.revision() + 1,
                    spec(latest.spec().models(), Optional.of(credential)),
                    latest.lifecycle());
            return new ProviderCredentialBinding(saved, new CredentialMetadata(credential, 1, DesktopTestFixtures.NOW));
        }

        private ProviderEndpoint updateProvider(JsonRpcRequest request) {
            WriteCommand command = fixture.json.decode(request.params(), WriteCommand.class);
            var payload = fixture.json.decode(command.payload(), ProviderRpcContracts.ProviderUpdatePayload.class);
            assertEquals(latest.revision(), command.expectedRevision());
            assertEquals(latest.spec().credential(), payload.spec().credential());
            assertTrue(credentialVerified);
            return save(request.method(), payload.id(), latest.revision() + 1, payload.spec(), payload.lifecycle());
        }

        private ProviderEndpoint save(
                String method, String id, long revision, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
            latest = new ProviderEndpoint(
                    id, revision, lifecycle, spec, DesktopTestFixtures.NOW, DesktopTestFixtures.NOW);
            providers.put(revision, latest);
            fixture.mutations.add(method);
            return latest;
        }

        private void requireAvailable(ProviderRef provider) {
            ProviderEndpoint exact = providers.get(provider.endpointRevision());
            assertEquals(provider.endpointId(), exact.id());
            assertEquals(ProviderLifecycle.ACTIVE, exact.lifecycle());
            assertTrue(credentialVerified);
            assertTrue(exact.spec().credential().isPresent());
            assertTrue(exact.spec().models().stream()
                    .anyMatch(model -> model.modelId().equals(provider.model())));
        }

        private Object start(JsonRpcRequest request) {
            WriteCommand command = fixture.json.decode(request.params(), WriteCommand.class);
            sent = fixture.json.decode(command.payload(), CoreRpcContracts.TurnStartPayload.class);
            ProviderRef provider = fixture.configurations
                    .get(sent.threadId())
                    .overrides()
                    .provider()
                    .orElseThrow();
            requireAvailable(provider);
            assertEquals(fixture.server.thread().id(), sent.threadId());
            assertEquals(0, command.expectedRevision());
            started = turn(provider, TurnStatus.RUNNING, 1);
            fixture.mutations.add("turn/start");
            return new CoreRpcContracts.TurnStartResult(started, started.resolvedConfig());
        }

        private AgentTurn completed() {
            return turn(started.provider(), TurnStatus.COMPLETED, 2);
        }

        private AgentTurn turn(ProviderRef provider, TurnStatus status, long revision) {
            AgentTurn base = DesktopTestFixtures.turn(fixture.server.thread(), status, revision);
            ResolvedTurnConfigSummary config = base.resolvedConfig();
            var resolved = new ResolvedTurnConfigSummary(
                    config.role(),
                    provider,
                    config.permissionProfile(),
                    config.approvalPolicy(),
                    config.budget(),
                    config.effectiveCapabilities(),
                    config.reasoning(),
                    config.promptManifestDigest(),
                    config.toolCatalogDigest(),
                    false,
                    config.provenance());
            return new AgentTurn(
                    base.id(),
                    base.threadId(),
                    status,
                    revision,
                    base.budget(),
                    base.role(),
                    provider,
                    base.permissionProfile(),
                    base.executionRoot(),
                    base.promptManifestDigest(),
                    base.toolCatalogDigest(),
                    Optional.empty(),
                    base.createdAt(),
                    base.updatedAt(),
                    resolved);
        }

        private ExecutionConfiguration seedForeignThread() {
            var thread = new ConversationThread(
                    ThreadId.random(),
                    WorkspaceId.random(),
                    Optional.empty(),
                    ThreadExecutionIntent.WORKSPACE,
                    "另一个项目的对话",
                    ThreadStatus.ACTIVE,
                    1,
                    DesktopTestFixtures.NOW,
                    DesktopTestFixtures.NOW);
            fixture.threads.put(thread.id(), thread);
            var configuration = new ExecutionConfiguration(
                    Optional.of(thread.workspaceId()),
                    Optional.of(thread.id()),
                    DesktopModelPreferencesTest.constrained(),
                    4,
                    DesktopTestFixtures.NOW);
            fixture.configurations.put(thread.id(), configuration);
            return configuration;
        }

        @Override
        public void close() {
            secrets.close();
            fixture.close();
        }
    }
}
