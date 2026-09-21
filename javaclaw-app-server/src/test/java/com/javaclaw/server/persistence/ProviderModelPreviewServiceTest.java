package com.javaclaw.server.persistence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderModelDiscoveryOperationState;
import com.javaclaw.api.ProviderModelDiscoveryRequest;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.model.CredentialMaterial;
import com.javaclaw.model.ProviderModelPreviewException;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.ProviderModelDiscoveryRpcContracts;

import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.await;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.identity;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.request;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.result;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.startIdentity;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelPreviewServiceTest {
    @TempDir
    Path directory;

    @Test
    void 草稿预览不写入Provider或Vault且重复启动不再解封() throws Exception {
        AtomicInteger unseals = new AtomicInteger();
        AtomicReference<CredentialMaterial> borrowed = new AtomicReference<>();
        byte[] secret = "temporary-preview-key".getBytes(StandardCharsets.UTF_8);
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> {
                    borrowed.set(material.orElseThrow());
                    assertArrayEquals(
                            "temporary-preview-key".toCharArray(),
                            borrowed.get().copy());
                    return result(request);
                })) {
            ProviderModelPreviewRequest request = request(ProviderAuthentication.API_KEY);
            CommandIdentity identity = startIdentity("preview", request);
            var first = service.start("owner", identity, request, () -> {
                unseals.incrementAndGet();
                return secret;
            });
            var second = service.start("owner", identity, request, () -> {
                throw new AssertionError("重复 start 不得解封相同 envelope");
            });
            assertEquals(first.operationId(), second.operationId());
            assertEquals(
                    ProviderModelDiscoveryOperationState.SUCCEEDED,
                    await(service, "owner", first).state());
            assertEquals(1, unseals.get());
            assertArrayEquals(new byte[secret.length], secret);
            assertThrows(IllegalStateException.class, () -> borrowed.get().copy());
            assertTrue(fixture.providers.listAllVersions().isEmpty());
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 共享Session容量且移除旧发现后可供预览使用() {
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> result(request))) {
            var endpoint = fixture.create(ProviderAuthentication.NONE);
            var discovery = new ProviderModelDiscoveryRequest(endpoint.id(), endpoint.revision());
            for (int index = 0; index < 3; index++) {
                fixture.saved.start(
                        "owner",
                        identity(
                                ProviderModelDiscoveryRpcContracts.START_METHOD,
                                "saved-" + index,
                                endpoint.revision(),
                                discovery),
                        discovery);
            }
            var request = request(ProviderAuthentication.NONE);
            service.start("owner", startIdentity("preview", request), request, () -> null);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("owner", startIdentity("overflow", request), request, () -> null));
            fixture.saved.cancelOwner("owner");
            var allowed = service.start("owner", startIdentity("after-release", request), request, () -> null);
            assertFalse(allowed.operationId().isBlank());
            assertEquals(1, fixture.providers.listAllVersions().size());
        }
    }

    @Test
    void 共享全局容量且不同入口无法额外创建第65个操作() {
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> result(request))) {
            var endpoint = fixture.create(ProviderAuthentication.NONE);
            var discovery = new ProviderModelDiscoveryRequest(endpoint.id(), endpoint.revision());
            for (int index = 0; index < 64; index++) {
                fixture.saved.start(
                        "owner-" + index,
                        identity(
                                ProviderModelDiscoveryRpcContracts.START_METHOD,
                                "saved-" + index,
                                endpoint.revision(),
                                discovery),
                        discovery);
            }
            var request = request(ProviderAuthentication.NONE);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("preview-owner", startIdentity("overflow", request), request, () -> null));
            fixture.saved.cancelOwner("owner-0");
            service.start("preview-owner", startIdentity("after-release", request), request, () -> null);
        }
    }

    @Test
    void 取消与Session关闭清零材料并隔离其他Session() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<CredentialMaterial> borrowed = new AtomicReference<>();
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> {
                    borrowed.set(material.orElseThrow());
                    entered.countDown();
                    while (!token.isCancelled()) {
                        java.util.concurrent.locks.LockSupport.parkNanos(
                                Duration.ofMillis(1).toNanos());
                    }
                    token.throwIfCancelled();
                    return result(request);
                })) {
            var request = request(ProviderAuthentication.API_KEY);
            var started = service.start(
                    "owner",
                    startIdentity("start", request),
                    request,
                    () -> "ephemeral".getBytes(StandardCharsets.UTF_8));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(PersistenceException.class, () -> service.read("other", started.operationId()));
            var payload = new ProviderModelDiscoveryRpcContracts.CancelPayload(
                    started.operationId(), ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED);
            var cancel = identity(ProviderConfigurationRpcContracts.PREVIEW_CANCEL_METHOD, "cancel", 1, payload);
            var cancelled = service.cancel("owner", cancel, started.operationId(), payload.reason());
            assertEquals(ProviderModelDiscoveryOperationState.CANCELLED, cancelled.state());
            assertEquals(cancelled, service.cancel("owner", cancel, started.operationId(), payload.reason()));
            assertThrows(IllegalStateException.class, () -> borrowed.get().copy());
            service.cancelOwner("owner");
            assertThrows(PersistenceException.class, () -> service.read("owner", started.operationId()));
        }
    }

    @Test
    void 失败材料被清零并仅保留脱敏类别() throws Exception {
        AtomicReference<CredentialMaterial> material = new AtomicReference<>();
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, value, token) -> {
                    material.set(value.orElseThrow());
                    throw new ProviderModelPreviewException(ProviderModelPreviewException.Code.AUTHENTICATION_FAILED);
                })) {
            var request = request(ProviderAuthentication.API_KEY);
            var started = service.start(
                    "owner",
                    startIdentity("start", request),
                    request,
                    () -> "ephemeral".getBytes(StandardCharsets.UTF_8));
            var failure = await(service, "owner", started);
            assertEquals(Optional.of("AUTHENTICATION_FAILED"), failure.failureCode());
            assertThrows(IllegalStateException.class, () -> material.get().copy());
        }
    }

    @Test
    void 拒绝错误版本和冲突幂等键且解封失败归还额度() {
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> result(request))) {
            var request = request(ProviderAuthentication.API_KEY);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start(
                            "owner",
                            identity(ProviderConfigurationRpcContracts.PREVIEW_START_METHOD, "stale", 0, request),
                            request,
                            () -> null));
            for (int index = 0; index < 5; index++) {
                String key = "invalid-" + index;
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.start("owner", startIdentity(key, request), request, () -> {
                            throw new IllegalArgumentException("rejected");
                        }));
            }
            var none = request(ProviderAuthentication.NONE);
            service.start("owner", startIdentity("same", none), none, () -> null);
            var conflict = assertThrows(
                    PersistenceException.class,
                    () -> service.start("owner", startIdentity("same", request), request, () -> null));
            assertEquals(PersistenceException.Kind.IDEMPOTENCY_CONFLICT, conflict.kind());
        }
    }
}
