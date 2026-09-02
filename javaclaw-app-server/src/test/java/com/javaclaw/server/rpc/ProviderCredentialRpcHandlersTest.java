package com.javaclaw.server.rpc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.api.ProviderCredentialClearResult;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderRole;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CredentialRpcContracts;
import com.javaclaw.protocol.ProviderCredentialRpcContracts;
import com.javaclaw.protocol.SessionSecretChannel;
import com.javaclaw.protocol.SessionSecretSealer;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.ProviderCredentialService;
import com.javaclaw.server.persistence.ProviderService;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCredentialRpcHandlersTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-01T08:00:00Z"), ZoneOffset.UTC);
    private static final String SECRET = "rpc-provider-secret";

    @TempDir
    Path temporaryDirectory;

    @Test
    void 绑定重放不二次解封且清除原子返回Provider新版本() throws Exception {
        CanonicalJson json = new CanonicalJson();
        H2Database database = database();
        try (SecretVaultService vault = vault(database, json);
                SessionSecretChannel secrets = SessionSecretChannel.open()) {
            ProviderService providers = providers(database, json);
            RpcRouter router = router(
                    new ProviderCredentialService(providers, vault.providerCredentials(), json, CLOCK), vault, json);
            WriteCommand set = setCommand(secrets, json);

            ProviderCredentialBinding first = json.decode(
                    router.route("provider/credential/set", json.encode(set), secrets),
                    ProviderCredentialBinding.class);
            ProviderCredentialBinding replay = json.decode(
                    router.route("provider/credential/set", json.encode(set), secrets),
                    ProviderCredentialBinding.class);

            assertEquals(first, replay);
            assertEquals(2, first.provider().revision());
            assertEquals(SECRET, vault.use(first.credential().reference(), ProviderCredentialRpcHandlersTest::text));
            WriteCommand clear = new WriteCommand(
                    "clear-provider-secret",
                    first.provider().revision(),
                    json.encode(new ProviderCredentialRpcContracts.ClearPayload(
                            first.provider().id(),
                            first.provider().revision(),
                            first.credential().reference(),
                            first.credential().revision())));
            ProviderCredentialClearResult cleared = json.decode(
                    router.route("provider/credential/clear", json.encode(clear), secrets),
                    ProviderCredentialClearResult.class);
            assertEquals(3, cleared.provider().revision());
            assertTrue(cleared.provider().spec().credential().isEmpty());
            assertEquals(0, vault.status().credentialCount());
        }
    }

    @Test
    void 通用Credential方法拒绝ProviderNamespace且不消费密文() throws Exception {
        CanonicalJson json = new CanonicalJson();
        H2Database database = database();
        try (SecretVaultService vault = vault(database, json);
                SessionSecretChannel secrets = SessionSecretChannel.open()) {
            RpcRouter router = router(
                    new ProviderCredentialService(providers(database, json), vault.providerCredentials(), json, CLOCK),
                    vault,
                    json);
            char[] characters = SECRET.toCharArray();
            var sealed = SessionSecretSealer.seal(secrets.publicKey(), "credential/provider/create", characters);
            Arrays.fill(characters, '\0');
            WriteCommand command = new WriteCommand(
                    "generic-provider-secret",
                    0,
                    json.encode(new CredentialRpcContracts.CreatePayload("provider", sealed)));

            assertThrows(
                    IllegalArgumentException.class,
                    () -> router.route("credential/create", json.encode(command), secrets));
            byte[] plaintext = secrets.unseal(sealed, "credential/provider/create");
            try {
                assertEquals(SECRET, text(plaintext));
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private H2Database database() {
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        return database;
    }

    private static SecretVaultService vault(H2Database database, CanonicalJson json) {
        return new SecretVaultService(database, new MemoryProtector(), json, CLOCK, new SecureRandom());
    }

    private static ProviderService providers(H2Database database, CanonicalJson json) {
        ProviderService providers = new ProviderService(database, json, CLOCK);
        ProviderEndpointSpec spec = new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.empty(),
                Set.of(ProviderRole.CHAT),
                List.of("test-model"),
                Optional.empty(),
                Duration.ofSeconds(30),
                0,
                Map.of());
        WriteCommand create = new WriteCommand(
                "create-provider",
                0,
                json.encode(new com.javaclaw.protocol.ProviderProfileRpcContracts.ProviderCreatePayload(
                        "provider-main", spec)));
        providers.create(CommandIdentity.from("provider/create", create, json), "provider-main", spec);
        return providers;
    }

    private static RpcRouter router(
            ProviderCredentialService providerCredentials, SecretVaultService vault, CanonicalJson json) {
        RpcRouter.Builder routes = RpcRouter.builder();
        new ProviderCredentialRpcHandlers(providerCredentials, json).register(routes);
        new CredentialRpcHandlers(vault, json).register(routes);
        return routes.build();
    }

    private static WriteCommand setCommand(SessionSecretChannel secrets, CanonicalJson json) {
        char[] characters = SECRET.toCharArray();
        var sealed =
                SessionSecretSealer.seal(secrets.publicKey(), ProviderCredentialRpcContracts.SET_PURPOSE, characters);
        Arrays.fill(characters, '\0');
        return new WriteCommand(
                "set-provider-secret",
                1,
                json.encode(new ProviderCredentialRpcContracts.SetPayload("provider-main", 1, 0, sealed)));
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
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
