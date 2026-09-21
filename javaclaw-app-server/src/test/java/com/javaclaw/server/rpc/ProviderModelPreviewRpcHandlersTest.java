package com.javaclaw.server.rpc;

import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderModelDiscoveryOperationState;
import com.javaclaw.api.ProviderModelPreviewOperation;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.model.ProviderModelDiscoveryAdapter;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.ProviderModelDiscoveryRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.SessionSecretSealer;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ProviderModelDiscoveryService;
import com.javaclaw.server.persistence.ProviderModelPreviewService;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.vault.ProviderModelPreviewCredentialReader;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelPreviewRpcHandlersTest {
    @TempDir
    Path directory;

    @Test
    void 重复启动先读取Memo且读取消限定当前Session() throws Exception {
        CanonicalJson json = new CanonicalJson();
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        try (var vault = new SecretVaultService(
                        database, new MemoryProtector(), json, Clock.systemUTC(), new SecureRandom());
                var owner = SessionSecretChannel.open();
                var other = SessionSecretChannel.open()) {
            ProviderService providers = new ProviderService(database, vault, json, Clock.systemUTC());
            var adapter = new ProviderModelDiscoveryAdapter(ignored -> Optional.empty(), Clock.systemUTC());
            try (var saved = new ProviderModelDiscoveryService(providers, adapter);
                    var service = new ProviderModelPreviewService(
                            providers, new ProviderModelPreviewCredentialReader(vault), adapter, saved)) {
                var handlers = new ProviderModelPreviewRpcHandlers(service, json);
                var router = handlers.register(RpcRouter.builder()).build();
                var command = sealedCommand(owner, json);
                var first = start(router, owner, json, command);
                var second = start(router, owner, json, command);
                assertEquals(first.operationId(), second.operationId());
                var read = json.encode(new ProviderModelDiscoveryRpcContracts.ReadPayload(first.operationId()));
                assertThrows(
                        RuntimeException.class,
                        () -> router.route(ProviderConfigurationRpcContracts.PREVIEW_READ_METHOD, read, other));
                assertEquals(
                        first.operationId(),
                        json.decode(
                                        router.route(
                                                ProviderConfigurationRpcContracts.PREVIEW_READ_METHOD, read, owner),
                                        ProviderModelPreviewOperation.class)
                                .operationId());
                var cancel = new WriteCommand(
                        "cancel",
                        first.revision(),
                        json.encode(new ProviderModelDiscoveryRpcContracts.CancelPayload(
                                first.operationId(), ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED)));
                var cancelled = json.decode(
                        router.route(
                                ProviderConfigurationRpcContracts.PREVIEW_CANCEL_METHOD, json.encode(cancel), owner),
                        ProviderModelPreviewOperation.class);
                assertTrue(cancelled.terminal());
                assertFalse(cancelled.state() == ProviderModelDiscoveryOperationState.SUCCEEDED);
                assertTrue(providers.listLatest().isEmpty());
                assertEquals(0, vault.status().credentialCount());
                assertFalse(handlers.registerClose(owner));
                owner.close();
                assertTrue(handlers.registerClose(owner));
            }
        }
    }

    private static ProviderModelPreviewOperation start(
            RpcRouter router, SessionSecretChannel session, CanonicalJson json, WriteCommand command) throws Exception {
        return json.decode(
                router.route(ProviderConfigurationRpcContracts.PREVIEW_START_METHOD, json.encode(command), session),
                ProviderModelPreviewOperation.class);
    }

    private static WriteCommand sealedCommand(SessionSecretChannel session, CanonicalJson json) {
        char[] secret = "temporary-preview-key".toCharArray();
        var sealed = SessionSecretSealer.seal(
                session.publicKey(), ProviderConfigurationRpcContracts.PREVIEW_PURPOSE, secret);
        Arrays.fill(secret, '\0');
        var connection = new ProviderConnectionSpec(
                "Preview",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://127.0.0.1:1/v1")),
                ProviderAuthentication.API_KEY,
                Duration.ofSeconds(1),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
        var request = new ProviderModelPreviewRequest(
                "draft", 1, connection, Optional.empty(), ProviderCredentialChange.REPLACE);
        return new WriteCommand(
                "start",
                request.generation(),
                json.encode(new ProviderConfigurationRpcContracts.PreviewPayload(request, Optional.of(sealed))));
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
            byte[] value = keys.remove(keyId);
            if (value != null) {
                Arrays.fill(value, (byte) 0);
            }
        }
    }
}
