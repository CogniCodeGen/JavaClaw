package com.javaclaw.plugin.runner;

import com.google.protobuf.Empty;
import com.javaclaw.plugin.protocol.v2.*;
import com.javaclaw.plugin.sdk.BearerTokens;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实宿主 gRPC 夹具验证后台租约分配、任务继承和固定截止。 */
class GrpcHostClientTest {
    @TempDir Path temp;
    private static final String TOKEN = "background-control-012345678901234567890123456789";
    private static final String HOST_TOKEN = "background-callback-012345678901234567890123456789";

    @Test void backgroundRootAndChildrenShareOneReceiptUntilTheLastTaskCompletes() throws Exception {
        try (var fixture = new Fixture(10_000, false)) {
            CountDownLatch childStarted = new CountDownLatch(1), release = new CountDownLatch(1);
            try {
                fixture.tasks.submit(() -> {
                    fixture.client.invoke(fixture.context("untrusted-channel"), "usage", "record", "{}", ignored -> { });
                    fixture.tasks.submit(() -> { childStarted.countDown(); release.await(); return null; });
                }).get(5, TimeUnit.SECONDS);
                assertTrue(childStarted.await(5, TimeUnit.SECONDS));
                assertEquals(1, fixture.opened.get());
                assertEquals("background:trusted", fixture.callback.get().getRunId());
                assertEquals(0, fixture.closed.get());
                fixture.tasks.beginDrain();
                assertThrows(RejectedExecutionException.class, () -> fixture.tasks.submit(() -> { }));
                release.countDown();
                assertTrue(fixture.receiptClosed.await(5, TimeUnit.SECONDS));
                assertEquals(1, fixture.closed.get());
            } finally { release.countDown(); }
        }
    }

    @Test void rpcChildrenInheritTheOriginalRunAndDoNotOpenBackgroundReceipts() throws Exception {
        try (var fixture = new Fixture(10_000, false)) {
            try (var scope = fixture.client.rpcScope(fixture.context("fixed-run"))) {
                fixture.tasks.submit(() -> fixture.client.invoke(fixture.context(""), "memory", "read", "{}", ignored -> { }))
                        .get(5, TimeUnit.SECONDS);
            }
            assertEquals("fixed-run", fixture.callback.get().getRunId());
            assertEquals(0, fixture.opened.get());
            assertEquals(0, fixture.closed.get());
        }
    }

    @Test void fixedReceiptDeadlineInterruptsTheRootAndItsAlreadyStartedChildren() throws Exception {
        try (var fixture = new Fixture(400, false)) {
            CountDownLatch started = new CountDownLatch(2), cancelled = new CountDownLatch(2);
            Future<?> root = fixture.tasks.submit(() -> {
                fixture.tasks.submit(() -> {
                    started.countDown();
                    try { Thread.sleep(60_000); }
                    catch (InterruptedException expected) { assertTrue(fixture.client.taskCancelled()); cancelled.countDown(); }
                });
                started.countDown();
                try { Thread.sleep(60_000); }
                catch (InterruptedException expected) { assertTrue(fixture.client.taskCancelled()); cancelled.countDown(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            root.get(5, TimeUnit.SECONDS);
            fixture.tasks.shutdown();
            assertTrue(fixture.tasks.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, fixture.tasks.activeCount());
            assertEquals(1, fixture.opened.get());
        }
    }

    @Test void cancellingAnRpcAlsoInterruptsItsSdkChildrenWithoutAllocatingAReceipt() throws Exception {
        try (var fixture = new Fixture(10_000, false)) {
            CountDownLatch started = new CountDownLatch(1), cancelled = new CountDownLatch(1);
            try (var scope = fixture.client.rpcScope(fixture.context("fixed-run"))) {
                Future<?> child = fixture.tasks.submit(() -> {
                    started.countDown();
                    try { Thread.sleep(60_000); }
                    catch (InterruptedException expected) { assertTrue(fixture.client.taskCancelled()); cancelled.countDown(); }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                scope.cancel();
                assertTrue(Thread.interrupted());
                assertTrue(cancelled.await(5, TimeUnit.SECONDS));
                child.get(5, TimeUnit.SECONDS);
            }
            assertEquals(0, fixture.opened.get());
        } finally { Thread.interrupted(); }
    }

    @Test void completingAnRpcInterruptsItsUnfinishedChildrenBeforeTheRunIsReleased() throws Exception {
        try (var fixture = new Fixture(10_000, false)) {
            CountDownLatch started = new CountDownLatch(1), cancelled = new CountDownLatch(1);
            Future<?> child;
            try (var scope = fixture.client.rpcScope(fixture.context("fixed-run"))) {
                child = fixture.tasks.submit(() -> {
                    started.countDown();
                    try { Thread.sleep(60_000); }
                    catch (InterruptedException expected) {
                        assertTrue(fixture.client.taskCancelled());
                        assertThrows(CancellationException.class, () -> fixture.client.invoke(
                                fixture.context(""), "memory", "read", "{}", ignored -> { }));
                        cancelled.countDown();
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
            }
            assertFalse(Thread.currentThread().isInterrupted());
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            child.get(5, TimeUnit.SECONDS);
            fixture.tasks.shutdown();
            assertTrue(fixture.tasks.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, fixture.tasks.activeCount());
            assertNull(fixture.callback.get());
            assertEquals(0, fixture.opened.get());
            assertEquals(0, fixture.closed.get());
        }
    }

    @Test void rejectedReceiptNeverRunsPluginCodeOrLeavesASubmittedFuturePending() throws Exception {
        try (var fixture = new Fixture(10_000, true)) {
            AtomicBoolean executed = new AtomicBoolean();
            Future<?> task = fixture.tasks.submit(() -> executed.set(true));
            var failure = assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
            assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(failure.getCause()).getCode());
            assertFalse(executed.get());
            fixture.tasks.shutdown();
            assertTrue(fixture.tasks.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, fixture.tasks.activeCount());
        }
    }

    private final class Fixture implements AutoCloseable {
        private final PluginIdentity identity = PluginIdentity.newBuilder().setPluginId("test").setWorkspaceId("workspace")
                .setArtifactHash("a".repeat(64)).setConfigurationRevision(1).setInstanceId("instance").build();
        private final AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
        private final AtomicReference<CallContext> callback = new AtomicReference<>();
        private final CountDownLatch receiptClosed = new CountDownLatch(1);
        private final Server server;
        private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
        private final GrpcHostClient client;
        private final TrackedPluginExecutor tasks;

        private Fixture(long ttl, boolean reject) throws Exception {
            server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0)).executor(handlers)
                    .addService(ServerInterceptors.intercept(new HostServicesGrpc.HostServicesImplBase() {
                        @Override public void openBackground(BackgroundLeaseRequest request, StreamObserver<BackgroundLease> response) {
                            assertEquals(identity, request.getContext().getIdentity());
                            assertEquals(1_800_000, request.getTtlMillis());
                            opened.incrementAndGet();
                            if (reject) { response.onError(Status.UNAVAILABLE.asRuntimeException()); return; }
                            response.onNext(BackgroundLease.newBuilder().setLeaseId("trusted-receipt").setRunId("background:trusted")
                                    .setExpiresAtEpochMillis(System.currentTimeMillis() + ttl).build());
                            response.onCompleted();
                        }
                        @Override public void closeBackground(BackgroundLeaseCloseRequest request, StreamObserver<Empty> response) {
                            assertEquals(identity, request.getIdentity()); assertEquals("trusted-receipt", request.getLeaseId());
                            closed.incrementAndGet(); receiptClosed.countDown();
                            response.onNext(Empty.getDefaultInstance()); response.onCompleted();
                        }
                        @Override public void invoke(CallRequest request, StreamObserver<RpcEvent> response) {
                            callback.set(request.getContext());
                            response.onNext(RpcEvent.newBuilder().setRequestId(request.getContext().getRequestId())
                                    .setEventType(EventType.COMPLETE).setSequence(1).setPayloadJson("{}").build());
                            response.onCompleted();
                        }
                    }, BearerTokens.server(HOST_TOKEN))).build().start();
            client = new GrpcHostClient(new RunnerBootstrap(identity, temp.resolve("unused.jar"), "a".repeat(64),
                    "com.test.Plugin", temp, server.getPort(), TOKEN, HOST_TOKEN));
            tasks = new TrackedPluginExecutor(() -> false, getClass()::getClassLoader, () -> { }, client::captureTask);
        }
        private CallContext context(String run) {
            return CallContext.newBuilder().setIdentity(identity).setRunId(run).setRequestId(UUID.randomUUID().toString())
                    .setDeadlineEpochMillis(System.currentTimeMillis() + 10_000).build();
        }
        @Override public void close() throws Exception {
            tasks.shutdownNow(); tasks.awaitTermination(5, TimeUnit.SECONDS); client.close();
            server.shutdownNow(); server.awaitTermination(5, TimeUnit.SECONDS); handlers.shutdownNow();
        }
    }
}
