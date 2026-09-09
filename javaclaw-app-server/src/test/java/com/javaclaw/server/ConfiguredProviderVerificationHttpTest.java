package com.javaclaw.server;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProviderRpcContracts;
import com.javaclaw.protocol.ProviderVerificationRpcContracts;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.rpc.AppServerSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredProviderVerificationHttpTest {
    private static final String MODEL = "local-chat-model";

    @TempDir
    Path temporaryDirectory;

    @Test
    void 显式验证通过真实注册表与SpringAi向回环端点发送精确模型且重放不重复请求() throws Exception {
        try (LocalVerificationHttpEndpoint endpoint = new LocalVerificationHttpEndpoint(200);
                AppServerBootstrap.Components components = bootstrap()) {
            AppServerSession session = initializedSession(components);
            ProviderRef provider = createProvider(session, components, endpoint);
            WriteCommand verification = command(components, provider);

            ProviderVerificationResult result = verify(session, components, verification);

            assertEquals(
                    ProviderVerificationState.SUCCEEDED,
                    result.state(),
                    () -> result.errorCode().toString());
            assertEquals(provider, result.provider());
            assertEquals(ProviderModelPurpose.CHAT, result.purpose());
            assertEquals(4, result.usage().orElseThrow().inputTokens());
            assertEquals(1, result.usage().orElseThrow().outputTokens());
            assertEquals(1, endpoint.requests().size(), "验证成功必须来自端点实际收到 HTTP 请求");
            assertRequest(endpoint.requests().getFirst());
            assertEquals(result, verify(session, components, verification));
            assertEquals(1, endpoint.requests().size(), "同一幂等确认不得重复调用模型");
        }
    }

    @Test
    void 真实Http拒绝映射为脱敏失败并可区分从未发送请求的路径() throws Exception {
        try (LocalVerificationHttpEndpoint endpoint = new LocalVerificationHttpEndpoint(401);
                AppServerBootstrap.Components components = bootstrap()) {
            AppServerSession session = initializedSession(components);
            ProviderRef provider = createProvider(session, components, endpoint);

            ProviderVerificationResult result = verify(session, components, command(components, provider));

            assertEquals(1, endpoint.requests().size(), "上游拒绝前必须确实发送 HTTP 请求");
            assertRequest(endpoint.requests().getFirst());
            assertEquals(ProviderVerificationState.FAILED, result.state());
            assertEquals(provider, result.provider());
            assertTrue(result.errorCode().isPresent());
            assertFalse(result.toString().contains("local rejection"));
        }
    }

    private AppServerBootstrap.Components bootstrap() {
        AppServerBootstrap.Foundation foundation = PlatformFoundationFactory.create(
                temporaryDirectory.resolve("data-v6"), Clock.systemUTC(), new MemoryProtector(), ignored -> {});
        return ConfiguredProviderBootstrap.create(foundation, ignored -> Optional.empty());
    }

    private static AppServerSession initializedSession(AppServerBootstrap.Components components) {
        AppServerSession session = components.newSession();
        InitializeParams params = new InitializeParams(
                3, new ClientInfo("http-verification-test", "6"), new CapabilityAdvertisement(Set.of(), Set.of()));
        JsonRpcResponse response = session.handle(new JsonRpcRequest(
                new RpcId("initialize"), "initialize/session", components.json().encode(params)));
        assertTrue(response.error().isEmpty(), () -> response.error().toString());
        return session;
    }

    private static ProviderRef createProvider(
            AppServerSession session,
            AppServerBootstrap.Components components,
            LocalVerificationHttpEndpoint endpoint) {
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "回环测试模型",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(endpoint.baseUri()),
                ProviderAuthentication.NONE,
                List.of(new ProviderModelSpec(MODEL, MODEL, Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                Optional.empty(),
                Duration.ofSeconds(10),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        ProviderRpcContracts.ProviderCreatePayload payload =
                new ProviderRpcContracts.ProviderCreatePayload("local-provider", spec, ProviderLifecycle.ACTIVE);
        WriteCommand command =
                new WriteCommand("create-local-provider", 0, components.json().encode(payload));
        JsonRpcResponse response = session.handle(new JsonRpcRequest(
                new RpcId("create"), "provider/create", components.json().encode(command)));
        assertTrue(response.error().isEmpty(), () -> response.error().toString());
        ProviderEndpoint saved = components.json().decode(response.result().orElseThrow(), ProviderEndpoint.class);
        return new ProviderRef(saved.id(), saved.revision(), MODEL);
    }

    private static WriteCommand command(AppServerBootstrap.Components components, ProviderRef provider) {
        var payload = new ProviderVerificationRpcContracts.VerifyPayload(
                provider, ProviderModelPurpose.CHAT, true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);
        return new WriteCommand(
                "verify-local-provider",
                provider.endpointRevision(),
                components.json().encode(payload));
    }

    private static ProviderVerificationResult verify(
            AppServerSession session, AppServerBootstrap.Components components, WriteCommand command) {
        JsonRpcResponse response = session.handle(new JsonRpcRequest(
                new RpcId("verify"),
                ProviderVerificationRpcContracts.METHOD,
                components.json().encode(command)));
        assertTrue(response.error().isEmpty(), () -> response.error().toString());
        return components.json().decode(response.result().orElseThrow(), ProviderVerificationResult.class);
    }

    private static void assertRequest(LocalVerificationHttpEndpoint.Request request) throws Exception {
        assertEquals("POST", request.method());
        assertEquals("/v1/chat/completions", request.path());
        assertTrue(request.authorization().isEmpty());
        JsonNode body = new ObjectMapper().readTree(request.body());
        assertEquals(MODEL, body.path("model").asText());
        assertTrue(body.path("stream").asBoolean());
        assertEquals(512, body.path("max_completion_tokens").asInt());
        assertEquals(2, body.path("messages").size());
        assertTrue(body.path("tools").isMissingNode()
                || body.path("tools").isNull()
                || body.path("tools").isEmpty());
    }

    private static final class MemoryProtector implements MasterKeyProtector {
        private final Map<String, byte[]> keys = new HashMap<>();

        @Override
        public Optional<byte[]> load(String keyId) {
            return Optional.ofNullable(keys.get(keyId)).map(byte[]::clone);
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
