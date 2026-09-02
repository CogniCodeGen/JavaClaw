package com.javaclaw.client;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcClientConnectionTest {
    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void 无Request时持续Reader也及时投递Notification() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        ScriptedRpcConnection rpc = successful();
        try (RpcClientConnection connection = new RpcClientConnection(rpc, JSON, ignored -> delivered.countDown())) {
            rpc.emit(new JsonRpcNotification("extension/event", new CanonicalPayload("{}")));

            assertTrue(delivered.await(1, TimeUnit.SECONDS));
        }
        assertTrue(rpc.closed());
    }

    @Test
    void 异常Notification消费者使连接失败关闭() throws Exception {
        ScriptedRpcConnection rpc = successful();
        try (RpcClientConnection connection = new RpcClientConnection(rpc, JSON, ignored -> {
            throw new IllegalStateException("consumer failed");
        })) {
            rpc.emit(new JsonRpcNotification("extension/event", new CanonicalPayload("{}")));
            await(rpc::closed, Duration.ofSeconds(2));

            assertThrows(UncheckedIOException.class, () -> connection.query("test/read", Map.of(), TestResult.class));
        }
    }

    @Test
    void 超时Notification消费者使连接失败关闭且不建立无界队列() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ScriptedRpcConnection rpc = successful();
        try (RpcClientConnection connection = new RpcClientConnection(rpc, JSON, ignored -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
        })) {
            rpc.emit(new JsonRpcNotification("extension/event", new CanonicalPayload("{}")));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            await(rpc::closed, Duration.ofSeconds(2));

            assertThrows(UncheckedIOException.class, () -> connection.query("test/read", Map.of(), TestResult.class));
        } finally {
            release.countDown();
        }
    }

    @Test
    void 并发调用保持单在途且错误Response标识失败关闭() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(request -> {
            int current = calls.incrementAndGet();
            if (current == 1) {
                firstEntered.countDown();
                awaitIgnoringInterrupt(releaseFirst);
            }
            return JsonRpcResponse.success(request.id(), JSON.encode(new TestResult("ok-" + current)));
        });
        try (RpcClientConnection connection = new RpcClientConnection(rpc, JSON, ignored -> {})) {
            var first = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> connection.query("test/read", Map.of(), TestResult.class));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            var second = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> connection.query("test/read", Map.of(), TestResult.class));
            Thread.sleep(50);
            assertEquals(1, calls.get());
            releaseFirst.countDown();

            assertEquals(new TestResult("ok-1"), first.get(1, TimeUnit.SECONDS));
            assertEquals(new TestResult("ok-2"), second.get(1, TimeUnit.SECONDS));
        }

        ScriptedRpcConnection mismatched = new ScriptedRpcConnection(
                request -> JsonRpcResponse.success(new RpcId("different"), JSON.encode(new TestResult("ignored"))));
        try (RpcClientConnection connection = new RpcClientConnection(mismatched, JSON, ignored -> {})) {
            assertThrows(UncheckedIOException.class, () -> connection.query("test/read", Map.of(), TestResult.class));
            assertTrue(mismatched.closed());
        }
    }

    private static ScriptedRpcConnection successful() {
        return new ScriptedRpcConnection(
                request -> JsonRpcResponse.success(request.id(), JSON.encode(new TestResult("ok"))));
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    private record TestResult(String value) {}
}
