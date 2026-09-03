package com.javaclaw.server.persistence;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.Workspace;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ProviderState;
import com.javaclaw.server.TurnContractFixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderModelPersistenceBoundaryTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final String MODEL_501 = "m".repeat(501);
    private static final String MODEL_1000 = "n".repeat(1_000);

    @TempDir
    Path temporaryDirectory;

    private H2Database database;
    private CanonicalJson json;
    private CoreCommandService core;
    private ProviderEndpoint endpoint;
    private Workspace workspace;

    @BeforeEach
    void initializeDataV5() {
        database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        json = new CanonicalJson();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        core = new CoreCommandService(database, json, clock);
        ProviderService providers = new ProviderService(database, reference -> true, json, clock);
        endpoint = providers.create(
                identity("provider/create", "provider-create", 0),
                "provider",
                providerSpec(),
                ProviderLifecycle.ACTIVE);
        workspace = core.createWorkspace(
                identity("workspace/create", "workspace-create", 0), "模型边界测试", temporaryDirectory.resolve("workspace"));
    }

    @Test
    void 模型标识501与1000字符可通过所有原始模型列持久化() throws Exception {
        assertColumnLength("AGENT_TURN", "MODEL", 1_000);
        assertColumnLength("PROVIDER_VERIFICATION", "MODEL", 1_000);
        assertColumnLength("EMBEDDING_BINDING", "MODEL", 1_000);

        persistModel(MODEL_501, 1);
        persistModel(MODEL_1000, 2);
    }

    @Test
    void ProviderState可持久化最长端点标识生成的路由键() throws Exception {
        assertColumnLength("PROVIDER_STATE", "MODEL_ID", 512);
        ProviderRef provider = new ProviderRef("p".repeat(240), Long.MAX_VALUE, "model");
        AgentTurn turn = createTurn("route-key", provider);
        String routeKey = provider.routeKey();
        ProviderState state = new ProviderState("provider", "opaque-v1", json.parse("{\"id\":\"state\"}"));

        try (var connection = database.open()) {
            ProviderStateRepository states = new ProviderStateRepository();
            states.save(connection, turn.id(), routeKey, state, 17, NOW);
            ProviderStateRepository.StoredProviderState stored =
                    states.latest(connection, turn.threadId(), routeKey).orElseThrow();

            assertEquals(routeKey, stored.modelId());
            assertEquals(325, routeKey.length());
            assertEquals(state, stored.state());
        }
    }

    private void persistModel(String modelId, long bindingRevision) throws Exception {
        ProviderRef provider = new ProviderRef(endpoint.id(), endpoint.revision(), modelId);
        AgentTurn turn = createTurn("model-" + bindingRevision, provider);
        assertEquals(modelId, core.findTurn(turn.id()).orElseThrow().provider().model());

        CommandIdentity verificationIdentity =
                identity("provider/verify", "verify-" + bindingRevision, endpoint.revision());
        EmbeddingBinding binding = new EmbeddingBinding(provider, bindingRevision, NOW);
        try (var connection = database.open()) {
            ProviderVerificationRepository verifications = new ProviderVerificationRepository();
            verifications.insertRunning(
                    connection, verificationIdentity, provider, ProviderModelPurpose.EMBEDDING, NOW);
            assertEquals(
                    modelId,
                    verifications
                            .find(connection, verificationIdentity.idempotencyKey(), false)
                            .orElseThrow()
                            .provider()
                            .model());

            EmbeddingBindingRepository bindings = new EmbeddingBindingRepository();
            if (bindingRevision == 1) {
                bindings.insert(connection, binding);
            } else {
                bindings.update(connection, binding, bindingRevision - 1);
            }
            assertEquals(
                    modelId,
                    bindings.find(connection, false).orElseThrow().provider().model());
        }
    }

    private AgentTurn createTurn(String suffix, ProviderRef provider) {
        var thread = core.createThread(
                identity("thread/create", "thread-" + suffix, 0),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                suffix);
        CorePayloads.Message message = new CorePayloads.Message(MessageRole.USER, suffix, List.of(), Optional.empty());
        TurnStartRequest request = new TurnStartRequest(
                thread.id(),
                new TurnBudget(4_000, 1_000, 2, 0, Duration.ofMinutes(1)),
                TurnContractFixtures.PROFILE,
                provider,
                TurnContractFixtures.PERMISSIONS,
                temporaryDirectory.resolve("workspace"),
                TurnContractFixtures.PROMPT_SNAPSHOT,
                TurnContractFixtures.TOOL_CATALOG,
                message,
                Optional.empty());
        return core.startTurn(identity("turn/start", "turn-" + suffix, 0), request);
    }

    private void assertColumnLength(String table, String column, int expected) throws Exception {
        try (var connection = database.open();
                var statement = connection.prepareStatement("""
                        SELECT CHARACTER_MAXIMUM_LENGTH
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_SCHEMA = 'CORE' AND TABLE_NAME = ? AND COLUMN_NAME = ?
                        """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals(expected, result.getInt(1));
            }
        }
    }

    private ProviderEndpointSpec providerSpec() {
        return new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://models.example.test/v1")),
                ProviderAuthentication.NONE,
                List.of(model(MODEL_501), model(MODEL_1000)),
                Optional.empty(),
                Duration.ofSeconds(30),
                1,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    private static ProviderModelSpec model(String modelId) {
        return new ProviderModelSpec(
                modelId,
                modelId,
                Set.of(ProviderModelPurpose.CHAT, ProviderModelPurpose.EMBEDDING),
                OptionalInt.empty());
    }

    private CommandIdentity identity(String method, String key, long expectedRevision) {
        CanonicalPayload payload = json.encode(Map.of("key", key));
        return CommandIdentity.from(method, new WriteCommand(key, expectedRevision, payload), json);
    }
}
