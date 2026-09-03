package com.javaclaw.server.persistence;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelDiscoveryOperation;
import com.javaclaw.api.ProviderModelDiscoveryOperationState;
import com.javaclaw.api.ProviderModelDiscoveryRequest;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ProviderModelDiscoveryRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelDiscoveryServiceV5Test {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void 同Session幂等启动且不同命令重新发现并在归档后拒绝() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        try (ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(
                fixture.providers(),
                (actual, token) -> {
                    token.throwIfCancelled();
                    calls.incrementAndGet();
                    return result(actual);
                },
                CLOCK)) {
            ProviderModelDiscoveryRequest request = request(fixture.endpoint());
            CommandIdentity firstIdentity =
                    identity("first", request, fixture.endpoint().revision());
            ProviderModelDiscoveryOperation first = service.start("session-a", firstIdentity, request);
            ProviderModelDiscoveryOperation retry = service.start("session-a", firstIdentity, request);

            assertEquals(first.operationId(), retry.operationId());
            assertEquals(
                    ProviderModelDiscoveryOperationState.SUCCEEDED,
                    awaitTerminal(service, "session-a", first).state());
            ProviderModelDiscoveryOperation second = service.start(
                    "session-a", identity("second", request, fixture.endpoint().revision()), request);
            assertNotEquals(first.operationId(), second.operationId());
            awaitTerminal(service, "session-a", second);
            assertEquals(2, calls.get());
            assertEquals(List.of(fixture.endpoint()), fixture.providers().listLatest());

            fixture.providers()
                    .archive(
                            identity(
                                    "provider/archive",
                                    "archive",
                                    fixture.endpoint().revision(),
                                    fixture.endpoint()),
                            fixture.endpoint().id());
            assertThrows(
                    PersistenceException.class,
                    () -> service.start(
                            "session-a",
                            identity("third", request, fixture.endpoint().revision()),
                            request));
        }
    }

    @Test
    void 幂等键不同Payload拒绝且操作只对所属Session可见() {
        Fixture fixture = fixture();
        CountDownLatch entered = new CountDownLatch(1);
        try (ProviderModelDiscoveryService service = blockingService(fixture, entered)) {
            ProviderModelDiscoveryRequest request = request(fixture.endpoint());
            ProviderModelDiscoveryOperation operation = service.start(
                    "session-a",
                    identity("same-key", request, fixture.endpoint().revision()),
                    request);
            assertEquals(
                    ProviderModelDiscoveryOperationState.RUNNING,
                    service.read("session-a", operation.operationId()).state());
            CommandIdentity differentPayload = identity(
                    ProviderModelDiscoveryRpcContracts.START_METHOD,
                    "same-key",
                    fixture.endpoint().revision(),
                    new ProviderModelDiscoveryRpcContracts.CancelPayload(
                            "other-operation", ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED));

            PersistenceException conflict = assertThrows(
                    PersistenceException.class, () -> service.start("session-a", differentPayload, request));
            assertEquals(PersistenceException.Kind.IDEMPOTENCY_CONFLICT, conflict.kind());
            assertThrows(PersistenceException.class, () -> service.read("session-b", operation.operationId()));
            service.cancelOwner("session-a");
        }
    }

    @Test
    void Cancel和Session关闭均触达正在执行的Token且Cancel幂等() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch observedCancellation = new CountDownLatch(1);
        ProviderModelDiscoveryService.DiscoveryPort blocking = (endpoint, token) -> {
            entered.countDown();
            while (!token.isCancelled()) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        Duration.ofMillis(5).toNanos());
            }
            observedCancellation.countDown();
            token.throwIfCancelled();
            throw new AssertionError("unreachable");
        };
        try (ProviderModelDiscoveryService service =
                new ProviderModelDiscoveryService(fixture.providers(), blocking, CLOCK)) {
            ProviderModelDiscoveryRequest request = request(fixture.endpoint());
            ProviderModelDiscoveryOperation operation =
                    service.start("session-a", identity("cancel-start", request, 1), request);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            CommandIdentity cancel = identity(
                    ProviderModelDiscoveryRpcContracts.CANCEL_METHOD,
                    "cancel-command",
                    operation.revision(),
                    new ProviderModelDiscoveryRpcContracts.CancelPayload(
                            operation.operationId(), ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED));

            ProviderModelDiscoveryOperation cancelled = service.cancel(
                    "session-a", cancel, operation.operationId(), ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED);
            ProviderModelDiscoveryOperation repeated = service.cancel(
                    "session-a", cancel, operation.operationId(), ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED);

            assertEquals(ProviderModelDiscoveryOperationState.CANCELLED, cancelled.state());
            assertEquals(cancelled, repeated);
            assertTrue(observedCancellation.await(1, TimeUnit.SECONDS));
            ProviderModelDiscoveryOperation another =
                    service.start("session-a", identity("another-start", request, 1), request);
            CommandIdentity conflictingCancel = identity(
                    ProviderModelDiscoveryRpcContracts.CANCEL_METHOD,
                    "cancel-command",
                    another.revision(),
                    new ProviderModelDiscoveryRpcContracts.CancelPayload(
                            another.operationId(), ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED));
            PersistenceException conflict = assertThrows(
                    PersistenceException.class,
                    () -> service.cancel(
                            "session-a",
                            conflictingCancel,
                            another.operationId(),
                            ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED));
            assertEquals(PersistenceException.Kind.IDEMPOTENCY_CONFLICT, conflict.kind());
            service.cancelOwner("session-a");
        }
    }

    @Test
    void 每Session活动操作数量有硬上限() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch entered = new CountDownLatch(4);
        try (ProviderModelDiscoveryService service = blockingService(fixture, entered)) {
            ProviderModelDiscoveryRequest request = request(fixture.endpoint());
            for (int index = 0; index < 4; index++) {
                service.start("session-a", identity("bounded-" + index, request, 1), request);
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("session-a", identity("bounded-overflow", request, 1), request));
            service.cancelOwner("session-a");
        }
    }

    @Test
    void 启动取消文本与关闭状态均执行严格边界校验() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch entered = new CountDownLatch(1);
        ProviderModelDiscoveryRequest request = request(fixture.endpoint());
        ProviderModelDiscoveryService service = blockingService(fixture, entered);
        try {
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("session-a", identity("wrong-revision", request, 0), request));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.start(" ", identity("blank-owner", request, 1), request));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.start("x".repeat(241), identity("long-owner", request, 1), request));

            ProviderModelDiscoveryOperation operation =
                    service.start("session-a", identity("cancel-revision-start", request, 1), request);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            CommandIdentity stale = identity(
                    ProviderModelDiscoveryRpcContracts.CANCEL_METHOD,
                    "stale-cancel",
                    operation.revision() + 1,
                    new ProviderModelDiscoveryRpcContracts.CancelPayload(
                            operation.operationId(), ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED));
            PersistenceException failure = assertThrows(
                    PersistenceException.class,
                    () -> service.cancel(
                            "session-a",
                            stale,
                            operation.operationId(),
                            ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED));
            assertEquals(PersistenceException.Kind.REVISION_CONFLICT, failure.kind());
        } finally {
            service.close();
        }

        service.close();
        assertThrows(
                IllegalStateException.class,
                () -> service.start("session-a", identity("after-close", request, 1), request));
    }

    @Test
    void 发现异常与端点错配都收敛为脱敏失败终态() throws Exception {
        Fixture fixture = fixture();
        ProviderModelDiscoveryRequest request = request(fixture.endpoint());
        try (ProviderModelDiscoveryService failed = new ProviderModelDiscoveryService(
                        fixture.providers(),
                        (endpoint, token) -> {
                            throw new IllegalStateException("remote detail must not escape");
                        },
                        CLOCK);
                ProviderModelDiscoveryService mismatched = new ProviderModelDiscoveryService(
                        fixture.providers(),
                        (endpoint, token) -> new ProviderModelDiscoveryResult(
                                "another-provider", endpoint.revision(), List.of(), false, NOW),
                        CLOCK)) {
            ProviderModelDiscoveryOperation failedResult = awaitTerminal(
                    failed, "session-a", failed.start("session-a", identity("failure", request, 1), request));
            ProviderModelDiscoveryOperation mismatchedResult = awaitTerminal(
                    mismatched, "session-b", mismatched.start("session-b", identity("mismatch", request, 1), request));

            assertEquals(ProviderModelDiscoveryOperationState.FAILED, failedResult.state());
            assertEquals(Optional.of("DISCOVERY_FAILED"), failedResult.failureCode());
            assertEquals(ProviderModelDiscoveryOperationState.FAILED, mismatchedResult.state());
            assertEquals(Optional.of("DISCOVERY_FAILED"), mismatchedResult.failureCode());
        }
    }

    private ProviderModelDiscoveryService blockingService(Fixture fixture, CountDownLatch entered) {
        return new ProviderModelDiscoveryService(
                fixture.providers(),
                (endpoint, token) -> {
                    entered.countDown();
                    while (!token.isCancelled()) {
                        java.util.concurrent.locks.LockSupport.parkNanos(
                                Duration.ofMillis(5).toNanos());
                    }
                    token.throwIfCancelled();
                    throw new AssertionError("unreachable");
                },
                CLOCK);
    }

    private Fixture fixture() {
        CanonicalJson json = new CanonicalJson();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        ProviderService providers = new ProviderService(database, reference -> true, json, CLOCK);
        ProviderEndpointSpec shell = shell();
        ProviderEndpoint endpoint = providers.create(
                identity("provider/create", "shell-create", 0, shell),
                "catalog-provider",
                shell,
                ProviderLifecycle.DISABLED);
        return new Fixture(providers, endpoint);
    }

    private static ProviderModelDiscoveryOperation awaitTerminal(
            ProviderModelDiscoveryService service, String owner, ProviderModelDiscoveryOperation operation)
            throws InterruptedException {
        ProviderModelDiscoveryOperation current = operation;
        for (int attempt = 0; attempt < 100 && !current.terminal(); attempt++) {
            Thread.sleep(5);
            current = service.read(owner, current.operationId());
        }
        assertTrue(current.terminal());
        return current;
    }

    private static ProviderModelDiscoveryRequest request(ProviderEndpoint endpoint) {
        return new ProviderModelDiscoveryRequest(endpoint.id(), endpoint.revision());
    }

    private static ProviderModelDiscoveryResult result(ProviderEndpoint endpoint) {
        return new ProviderModelDiscoveryResult(
                endpoint.id(),
                endpoint.revision(),
                List.of(new ProviderModelDiscoveryCandidate(
                        "remote-chat", "Remote Chat", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                false,
                NOW);
    }

    private static ProviderEndpointSpec shell() {
        return new ProviderEndpointSpec(
                "Catalog",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("http://127.0.0.1:11434/v1")),
                ProviderAuthentication.NONE,
                List.of(),
                Optional.empty(),
                Duration.ofSeconds(2),
                0,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    private static CommandIdentity identity(String key, Object payload, long expectedRevision) {
        return identity(ProviderModelDiscoveryRpcContracts.START_METHOD, key, expectedRevision, payload);
    }

    private static CommandIdentity identity(String method, String key, long expectedRevision, Object payload) {
        CanonicalJson json = new CanonicalJson();
        return CommandIdentity.from(method, new WriteCommand(key, expectedRevision, json.encode(payload)), json);
    }

    private record Fixture(ProviderService providers, ProviderEndpoint endpoint) {}
}
