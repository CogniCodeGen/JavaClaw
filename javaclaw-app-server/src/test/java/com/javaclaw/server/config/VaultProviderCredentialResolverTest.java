package com.javaclaw.server.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.model.CredentialMaterial;
import com.javaclaw.nativehost.credential.MasterKeyProtector;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.security.vault.SecretVaultService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultProviderCredentialResolverTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-02T04:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 合法Utf8凭据映射为短生命周期CredentialMaterial() {
        try (SecretVaultService vault = vault()) {
            CredentialMetadata metadata = vault.create(
                    identity("credential/create"), "provider", "密钥-secret".getBytes(StandardCharsets.UTF_8));
            VaultProviderCredentialResolver resolver = new VaultProviderCredentialResolver(vault);

            CredentialMaterial material = resolver.resolve(metadata.reference()).orElseThrow();

            assertArrayEquals("密钥-secret".toCharArray(), material.copy());
            material.close();
            assertThrows(IllegalStateException.class, material::copy);
        }
    }

    @Test
    void 缺失引用与非法Utf8均失败关闭() {
        try (SecretVaultService vault = vault()) {
            VaultProviderCredentialResolver resolver = new VaultProviderCredentialResolver(vault);
            assertTrue(
                    resolver.resolve(new CredentialRef("provider", "missing")).isEmpty());

            CredentialMetadata malformed =
                    vault.create(identity("credential/create"), "provider", new byte[] {(byte) 0xC3, 0x28});
            assertTrue(resolver.resolve(malformed.reference()).isEmpty());
        }
    }

    @Test
    void 空Vault引用和空依赖立即拒绝() {
        assertThrows(NullPointerException.class, () -> new VaultProviderCredentialResolver(null));
        try (SecretVaultService vault = vault()) {
            VaultProviderCredentialResolver resolver = new VaultProviderCredentialResolver(vault);
            assertThrows(NullPointerException.class, () -> resolver.resolve(null));
        }
    }

    private SecretVaultService vault() {
        H2Database database = new H2Database(temporaryDirectory.resolve(UUID.randomUUID() + "/data-v6"));
        database.initialize();
        return new SecretVaultService(database, new MemoryProtector(), new CanonicalJson(), CLOCK, new SecureRandom());
    }

    private static CommandIdentity identity(String method) {
        return new CommandIdentity(method, UUID.randomUUID().toString(), 0, "0".repeat(64));
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
            keys.remove(keyId);
        }
    }
}
