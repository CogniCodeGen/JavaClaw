package com.javaclaw.server.persistence;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderModelDiscoveryOperationState;
import com.javaclaw.api.ProviderModelPreviewResult;
import com.javaclaw.protocol.ProviderConfigurationRpcContracts;
import com.javaclaw.protocol.ProviderModelDiscoveryRpcContracts;

import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.await;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.identity;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.request;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.result;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.startIdentity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelPreviewBoundariesTest {
    @TempDir
    Path directory;

    @Test
    void 取消版本及Memo上限受约束且迟到成功不能覆盖取消() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> {
                    entered.countDown();
                    while (!token.isCancelled()) {
                        java.util.concurrent.locks.LockSupport.parkNanos(
                                Duration.ofMillis(1).toNanos());
                    }
                    exited.countDown();
                    return result(request);
                })) {
            var request = request(ProviderAuthentication.NONE);
            var started = service.start("owner", startIdentity("start", request), request, () -> null);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var payload = new ProviderModelDiscoveryRpcContracts.CancelPayload(
                    started.operationId(), ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED);
            var stale = identity(ProviderConfigurationRpcContracts.PREVIEW_CANCEL_METHOD, "stale", 9, payload);
            assertThrows(
                    PersistenceException.class,
                    () -> service.cancel("owner", stale, started.operationId(), payload.reason()));
            for (int index = 0; index < 8; index++) {
                var cancel = identity(
                        ProviderConfigurationRpcContracts.PREVIEW_CANCEL_METHOD,
                        "cancel-" + index,
                        started.revision(),
                        payload);
                service.cancel("owner", cancel, started.operationId(), payload.reason());
            }
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            var overflow = identity(ProviderConfigurationRpcContracts.PREVIEW_CANCEL_METHOD, "overflow", 1, payload);
            assertThrows(
                    PersistenceException.class,
                    () -> service.cancel("owner", overflow, started.operationId(), payload.reason()));
            var conflict = identity(ProviderConfigurationRpcContracts.PREVIEW_CANCEL_METHOD, "cancel-0", 1, request);
            assertThrows(
                    PersistenceException.class,
                    () -> service.cancel("owner", conflict, started.operationId(), payload.reason()));
            assertEquals(
                    ProviderModelDiscoveryOperationState.CANCELLED,
                    service.read("owner", started.operationId()).state());
        }
    }

    @Test
    void 读取终态后清理Memo并归还共享容量() throws Exception {
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> result(request))) {
            var request = request(ProviderAuthentication.NONE);
            var identity = startIdentity("start", request);
            var started = service.start("owner", identity, request, () -> null);
            await(service, "owner", started);
            boolean removed = false;
            for (int attempt = 0; attempt < 200 && !removed; attempt++) {
                Thread.sleep(10);
                try {
                    service.read("owner", started.operationId());
                } catch (PersistenceException unavailable) {
                    removed = true;
                }
            }
            assertTrue(removed);
            assertNotEquals(
                    started.operationId(),
                    service.start("owner", identity, request, () -> null).operationId());
        }
    }

    @Test
    void 空白过长标识关闭服务与错配结果均明确失败() throws Exception {
        try (var fixture = new ProviderModelPreviewFixture(directory)) {
            var service = fixture.preview((request, material, token) -> result(request));
            var request = request(ProviderAuthentication.NONE);
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.start(" ", startIdentity("blank", request), request, () -> null));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.start("x".repeat(241), startIdentity("long", request), request, () -> null));
                assertThrows(PersistenceException.class, () -> service.read("owner", "missing"));
            } finally {
                service.close();
            }
            service.close();
            assertThrows(
                    IllegalStateException.class,
                    () -> service.start("owner", startIdentity("closed", request), request, () -> null));
            try (var mismatched = fixture.preview((draft, material, token) -> new ProviderModelPreviewResult(
                    "other", draft.generation(), List.of(), false, ProviderModelPreviewFixture.NOW))) {
                var operation = await(
                        mismatched,
                        "owner",
                        mismatched.start("owner", startIdentity("mismatch", request), request, () -> null));
                assertEquals(Optional.of("PREVIEW_FAILED"), operation.failureCode());
            }
        }
    }

    @Test
    void 普通异常与错误代次仅产生失败码() throws Exception {
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var failure = fixture.preview((request, material, token) -> {
                    throw new IllegalStateException("remote secret must not escape");
                });
                var mismatched = fixture.preview((request, material, token) -> new ProviderModelPreviewResult(
                        request.draftId(),
                        request.generation() + 1,
                        List.of(),
                        false,
                        ProviderModelPreviewFixture.NOW))) {
            var request = request(ProviderAuthentication.NONE);
            for (var service : List.of(failure, mismatched)) {
                var result = await(
                        service, "owner", service.start("owner", startIdentity("start", request), request, () -> null));
                assertEquals(ProviderModelDiscoveryOperationState.FAILED, result.state());
                assertEquals(Optional.of("PREVIEW_FAILED"), result.failureCode());
            }
        }
    }
}
