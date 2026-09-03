package com.javaclaw.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AgentProfile;
import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.api.Workspace;
import com.javaclaw.extension.spi.EmbeddingPort;
import com.javaclaw.model.CredentialMaterial;
import com.javaclaw.model.ProviderCredentialResolver;
import com.javaclaw.model.ProviderEmbeddingAdapterFactory;
import com.javaclaw.model.ProviderModelDiscoveryAdapter;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelStreamEvent;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.config.ProviderModelRegistry;
import com.javaclaw.server.config.VaultProviderCredentialResolver;
import com.javaclaw.server.extension.BuiltinIsolatedServices;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.ProviderModelDiscoveryService;
import com.javaclaw.server.rpc.AppServerSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredProviderRestartChainTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC);
    private static final String PROVIDER_ID = "restart-provider";
    private static final String MODEL = "restart-model";
    private static final String PROFILE_ID = "restart-profile";
    private static final String SECRET = "restart-secret";

    @TempDir
    Path temporaryDirectory;

    @Test
    void providerSecret与Profile在重启后继续驱动精确Turn() throws Exception {
        Path dataRoot = temporaryDirectory.resolve("data-v5");
        Files.createDirectories(temporaryDirectory.resolve("workspace"));
        MemoryProtector protector = new MemoryProtector();
        AtomicInteger invocations = new AtomicInteger();
        PersistentReferences references;

        try (Boot first = bootstrap(dataRoot, protector, invocations)) {
            references = configure(first);
            AgentTurn completed = runTurn(first.components(), references, "first-turn", "第一次执行");
            assertEquals(TurnStatus.COMPLETED, completed.status());
        }

        try (Boot restarted = bootstrap(dataRoot, protector, invocations)) {
            AgentTurn completed = runTurn(restarted.components(), references, "second-turn", "重启后执行");
            assertEquals(TurnStatus.COMPLETED, completed.status());
            assertEquals(
                    references.profile(),
                    profileRef(restarted.foundation().agentProfiles().require(PROFILE_ID, 1)));
            ProviderEndpoint provider = restarted.foundation().providers().require(PROVIDER_ID, 3);
            assertTrue(provider.spec().credential().isPresent());
        }

        assertEquals(2, invocations.get());
    }

    private PersistentReferences configure(Boot boot) {
        AppServerBootstrap.Foundation foundation = boot.foundation();
        ProviderEndpoint first = foundation
                .providers()
                .create(
                        identity("provider/create", "provider", 0, 'a'),
                        PROVIDER_ID,
                        providerSpec(),
                        ProviderLifecycle.DISABLED);
        ProviderEndpoint credentialBound = foundation
                .providerCredentials()
                .set(
                        identity("provider/credential/set", "credential", first.revision(), 'b'),
                        PROVIDER_ID,
                        first.revision(),
                        0,
                        SECRET.getBytes(StandardCharsets.UTF_8))
                .provider();
        ProviderEndpoint bound = foundation
                .providers()
                .update(
                        identity("provider/update", "enable-provider", credentialBound.revision(), 'c'),
                        PROVIDER_ID,
                        credentialBound.spec(),
                        ProviderLifecycle.ACTIVE);
        AgentProfile profile = foundation
                .agentProfiles()
                .create(
                        identity("profile/create", "profile", 0, 'd'),
                        PROFILE_ID,
                        new AgentProfileSpec(
                                "Restart Profile",
                                "保持回答简短。",
                                new ProviderRef(PROVIDER_ID, bound.revision(), MODEL),
                                new PermissionProfileRef("standard", 1),
                                Set.of(),
                                new TurnBudget(4_000, 1_000, 2, 0, Duration.ofSeconds(30))));

        AppServerSession session = boot.components().newSession();
        initialize(session, boot.components());
        Workspace workspace = createWorkspace(session, boot.components());
        ConversationThread thread = createThread(session, boot.components(), workspace);
        return new PersistentReferences(thread, profileRef(profile));
    }

    private Boot bootstrap(Path dataRoot, MasterKeyProtector protector, AtomicInteger invocations) {
        AppServerBootstrap.Foundation foundation =
                PlatformFoundationFactory.create(dataRoot, CLOCK, protector, required -> {});
        ProviderCredentialResolver credentials = new VaultProviderCredentialResolver(foundation.vault());
        ProviderModelRegistry models = new ProviderModelRegistry(
                foundation.providers(),
                (endpoint, reference) -> new CredentialCheckingModel(endpoint, reference, credentials, invocations),
                foundation.vault().runtimeGate());
        foundation.vault().onRuntimeChange(models::invalidate, models::reload);
        try (StartupCloseStack startup = new StartupCloseStack()) {
            AppServerBootstrap.ownFoundation(startup, foundation);
            AppServerBootstrap.Components components = AppServerBootstrap.createReal(
                    foundation,
                    new AppServerRuntimeBootstrap.RuntimeDependencies(
                            models,
                            EmbeddingPort.unavailable(),
                            new ProviderEmbeddingAdapterFactory(credentials)::create,
                            new ProviderModelDiscoveryService(
                                    foundation.providers(), new ProviderModelDiscoveryAdapter(credentials, CLOCK)),
                            BuiltinIsolatedServices.browserUnavailable(),
                            AppServerBootstrap.productionMcpPorts(foundation)),
                    startup);
            startup.releaseAll();
            return new Boot(foundation, components);
        }
    }

    private AgentTurn runTurn(
            AppServerBootstrap.Components components, PersistentReferences references, String key, String message)
            throws InterruptedException {
        AppServerSession session = components.newSession();
        initialize(session, components);
        CoreRpcContracts.TurnStartPayload payload = new CoreRpcContracts.TurnStartPayload(
                references.thread().id(), Optional.of(references.profile()), message);
        AgentTurn accepted = decode(
                session.handle(request(
                        components,
                        key,
                        "turn/start",
                        new WriteCommand(key, 0, components.json().encode(payload)))),
                components,
                AgentTurn.class);
        return awaitTerminal(session, components, accepted.id());
    }

    private Workspace createWorkspace(AppServerSession session, AppServerBootstrap.Components components) {
        CoreRpcContracts.WorkspaceCreatePayload payload =
                new CoreRpcContracts.WorkspaceCreatePayload("重启链工作区", temporaryDirectory.resolve("workspace"));
        return decode(
                session.handle(request(
                        components,
                        "workspace",
                        "workspace/create",
                        new WriteCommand("workspace", 0, components.json().encode(payload)))),
                components,
                Workspace.class);
    }

    private ConversationThread createThread(
            AppServerSession session, AppServerBootstrap.Components components, Workspace workspace) {
        CoreRpcContracts.ThreadCreatePayload payload = new CoreRpcContracts.ThreadCreatePayload(
                workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "重启链");
        return decode(
                session.handle(request(
                        components,
                        "thread",
                        "thread/create",
                        new WriteCommand("thread", 0, components.json().encode(payload)))),
                components,
                ConversationThread.class);
    }

    private AgentTurn awaitTerminal(AppServerSession session, AppServerBootstrap.Components components, TurnId turnId)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            AgentTurn turn = decode(
                    session.handle(request(
                            components, "read-" + attempt, "turn/read", new CoreRpcContracts.TurnQuery(turnId))),
                    components,
                    AgentTurn.class);
            if (turn.status() == TurnStatus.COMPLETED || turn.status() == TurnStatus.FAILED) {
                return turn;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Turn 未在期限内完成");
    }

    private static ProviderEndpointSpec providerSpec() {
        return ProviderEndpointTestFixtures.apiKeyChat("Restart Provider", ProviderAdapter.OPENAI_COMPATIBLE, MODEL);
    }

    private static CommandIdentity identity(String method, String key, long revision, char digest) {
        return new CommandIdentity(
                method, key, revision, Character.toString(digest).repeat(64));
    }

    private static AgentProfileRef profileRef(AgentProfile profile) {
        return new AgentProfileRef(profile.id(), profile.revision());
    }

    private static void initialize(AppServerSession session, AppServerBootstrap.Components components) {
        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("restart-chain-test", "5.0"),
                new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
        assertTrue(session.handle(request(components, "init", "initialize/session", params))
                .result()
                .isPresent());
    }

    private static JsonRpcRequest request(
            AppServerBootstrap.Components components, String id, String method, Object params) {
        return new JsonRpcRequest(new RpcId(id), method, components.json().encode(params));
    }

    private static <T> T decode(JsonRpcResponse response, AppServerBootstrap.Components components, Class<T> type) {
        return components
                .json()
                .decode(
                        response.result()
                                .orElseThrow(() ->
                                        new AssertionError(response.error().orElseThrow())),
                        type);
    }

    private record PersistentReferences(ConversationThread thread, AgentProfileRef profile) {}

    private record Boot(AppServerBootstrap.Foundation foundation, AppServerBootstrap.Components components)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            components.close();
        }
    }

    private static final class CredentialCheckingModel implements ModelGateway {
        private final ProviderEndpoint endpoint;
        private final ProviderRef reference;
        private final ProviderCredentialResolver credentials;
        private final AtomicInteger invocations;

        private CredentialCheckingModel(
                ProviderEndpoint endpoint,
                ProviderRef reference,
                ProviderCredentialResolver credentials,
                AtomicInteger invocations) {
            this.endpoint = endpoint;
            this.reference = reference;
            this.credentials = credentials;
            this.invocations = invocations;
        }

        @Override
        public ModelCapabilities capabilities(String modelId) {
            requireRoute(modelId);
            return new ModelCapabilities(true, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                com.javaclaw.api.CancellationToken cancellation)
                throws InterruptedException {
            requireRoute(invocation.modelId());
            requireCredential();
            invocations.incrementAndGet();
            events.publish(turnId, new ModelStreamEvent.TextDelta("已完成"), cancellation);
            ModelUsage usage = new ModelUsage(16, 4, 0, 0);
            events.publish(turnId, new ModelStreamEvent.Usage(usage), cancellation);
            return new ModelInvocationResult(
                    "已完成", List.of(), usage, Optional.empty(), Optional.empty(), ModelFinishReason.COMPLETE);
        }

        private void requireRoute(String route) {
            if (!reference.routeKey().equals(route)) {
                throw new IllegalArgumentException("模型路由不匹配");
            }
        }

        private void requireCredential() {
            CredentialRef credential = endpoint.spec().credential().orElseThrow();
            try (CredentialMaterial material = credentials.resolve(credential).orElseThrow()) {
                char[] value = material.copy();
                try {
                    if (!matchesSecret(value)) {
                        throw new IllegalStateException("Provider 凭据恢复值不匹配");
                    }
                } finally {
                    Arrays.fill(value, '\0');
                }
            }
        }

        private static boolean matchesSecret(char[] value) {
            if (value.length != SECRET.length()) {
                return false;
            }
            for (int index = 0; index < value.length; index++) {
                if (value[index] != SECRET.charAt(index)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public Optional<byte[]> load(String keyId) {
            byte[] value = keys.get(keyId);
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }

        @Override
        public void store(String keyId, byte[] key) {
            keys.put(keyId, key.clone());
        }

        @Override
        public void delete(String keyId) {
            byte[] removed = keys.remove(keyId);
            if (removed != null) {
                Arrays.fill(removed, (byte) 0);
            }
        }
    }
}
