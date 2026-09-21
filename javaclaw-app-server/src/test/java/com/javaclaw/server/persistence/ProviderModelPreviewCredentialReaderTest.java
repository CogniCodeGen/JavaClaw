package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderCredentialBinding;
import com.javaclaw.server.security.vault.ProviderModelPreviewCredentialReader;

import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.identity;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelPreviewCredentialReaderTest {
    @TempDir
    Path directory;

    @Test
    void 只核对版本时不读取材料且空引用和错误Namespace明确拒绝() {
        try (var fixture = new ProviderModelPreviewFixture(directory)) {
            var reader = new ProviderModelPreviewCredentialReader(fixture.vault);
            assertTrue(reader.read(Optional.empty(), 0, false).isEmpty());
            assertThrows(PersistenceException.class, () -> reader.read(Optional.empty(), 0, true));
            assertThrows(PersistenceException.class, () -> reader.read(Optional.empty(), -1, false));
            assertThrows(PersistenceException.class, () -> reader.read(Optional.empty(), 1, false));
            assertThrows(
                    PersistenceException.class,
                    () -> reader.read(Optional.of(new CredentialRef("http", "foreign")), 0, false));
            var binding = bind(fixture, "saved-key".getBytes(StandardCharsets.UTF_8));
            var reference = Optional.of(binding.credential().reference());
            assertTrue(reader.read(reference, 1, false).isEmpty());
            try (var material = reader.read(reference, 1, true).orElseThrow()) {
                assertArrayEquals("saved-key".toCharArray(), material.copy());
            }
            assertThrows(PersistenceException.class, () -> reader.read(reference, 2, true));
        }
    }

    @Test
    void Vault中非法Utf8不会被转换成可使用的模型凭据() {
        try (var fixture = new ProviderModelPreviewFixture(directory)) {
            var binding = bind(fixture, new byte[] {(byte) 0xc3, (byte) 0x28});
            var reader = new ProviderModelPreviewCredentialReader(fixture.vault);
            assertThrows(
                    PersistenceException.class,
                    () -> reader.read(Optional.of(binding.credential().reference()), 1, true));
            assertEquals(1, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 等待Vault锁的旧版本读取不能借出并发轮换后的材料() throws Exception {
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var binding = bind(fixture, "first-key".getBytes(StandardCharsets.UTF_8));
            var reference = binding.credential().reference();
            var reader = new ProviderModelPreviewCredentialReader(fixture.vault);
            CountDownLatch requested = new CountDownLatch(1);
            java.util.concurrent.Future<?> result;
            synchronized (fixture.vault) {
                result = executor.submit(() -> {
                    requested.countDown();
                    return reader.read(Optional.of(reference), 1, true);
                });
                assertTrue(requested.await(1, TimeUnit.SECONDS));
                assertFalse(result.isDone());
                byte[] replacement = "second-key".getBytes(StandardCharsets.UTF_8);
                try (var prepared =
                        fixture.vault.providerCredentials().prepare(Optional.of(reference), 1, replacement)) {
                    fixture.vault
                            .providerCredentials()
                            .commit(
                                    identity("provider/credential/set", "rotate", 1, binding),
                                    prepared,
                                    com.javaclaw.api.CredentialMetadata.class,
                                    connection -> prepared.metadata());
                } finally {
                    java.util.Arrays.fill(replacement, (byte) 0);
                }
            }
            var failure =
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof PersistenceException);
            assertEquals(
                    PersistenceException.Kind.REVISION_CONFLICT, ((PersistenceException) failure.getCause()).kind());
            assertEquals(2, fixture.vault.metadata(reference).orElseThrow().revision());
        }
    }

    private static ProviderCredentialBinding bind(ProviderModelPreviewFixture fixture, byte[] secret) {
        var shell = fixture.create(ProviderAuthentication.API_KEY);
        var service = new ProviderCredentialService(
                fixture.providers,
                fixture.vault.providerCredentials(),
                fixture.json,
                ProviderModelPreviewFixture.CLOCK);
        return service.set(
                identity("provider/credential/set", "bind", shell.revision(), shell),
                shell.id(),
                shell.revision(),
                0,
                secret);
    }
}
