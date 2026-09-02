package com.javaclaw.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.RpcId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcClientConnectionFailureTest {
    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void closedConnectionRejectsCallsAndCloseIsIdempotent() throws Exception {
        ScriptedRpcConnection rpc = new ScriptedRpcConnection(
                request -> JsonRpcResponse.success(request.id(), JSON.encode(new Result("ok"))));
        RpcClientConnection connection = new RpcClientConnection(rpc, JSON, ignored -> {});

        connection.close();
        connection.close();

        assertThrows(IllegalStateException.class, () -> connection.query("test/read", Map.of(), Result.class));
        assertTrue(rpc.closed());
    }

    @Test
    void readerRejectsServerRequestsAndUnsolicitedResponses() throws Exception {
        assertProtocolViolation(new JsonRpcRequest(new RpcId("server"), "workspace/list", new CanonicalPayload("{}")));
        assertProtocolViolation(JsonRpcResponse.success(new RpcId("orphan"), new CanonicalPayload("{}")));
    }

    @Test
    void transportFailurePreservesCloseFailureAsSuppressed() throws Exception {
        IOException receiveFailure = new IOException("receive failed");
        IOException closeFailure = new IOException("close failed");
        ReceiveFailureConnection rpc = new ReceiveFailureConnection(receiveFailure, closeFailure);
        RpcClientConnection connection = new RpcClientConnection(rpc, JSON, ignored -> {});
        assertTrue(rpc.closed.await(1, TimeUnit.SECONDS));

        UncheckedIOException thrown =
                assertThrows(UncheckedIOException.class, () -> connection.query("test/read", Map.of(), Result.class));

        assertSame(receiveFailure, thrown.getCause());
        assertEquals(1, receiveFailure.getSuppressed().length);
        assertSame(closeFailure, receiveFailure.getSuppressed()[0]);
        assertSame(closeFailure, assertThrows(IOException.class, connection::close));
    }

    @Test
    void interruptedCallerFailsPendingRequestAndRestoresInterrupt() throws Exception {
        ManualConnection rpc = new ManualConnection();
        RpcClientConnection connection = new RpcClientConnection(rpc, JSON, ignored -> {});
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                connection.query("test/read", Map.of(), Result.class);
            } catch (Throwable thrown) {
                failure.set(thrown);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        assertTrue(rpc.sent.await(1, TimeUnit.SECONDS));

        caller.interrupt();
        caller.join(1_000);

        assertTrue(failure.get() instanceof UncheckedIOException);
        assertTrue(interrupted.get());
        assertTrue(rpc.closed);
    }

    @Test
    void closeCompletesPendingRequestExceptionally() throws Exception {
        ManualConnection rpc = new ManualConnection();
        RpcClientConnection connection = new RpcClientConnection(rpc, JSON, ignored -> {});
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                connection.query("test/read", Map.of(), Result.class);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        assertTrue(rpc.sent.await(1, TimeUnit.SECONDS));

        connection.close();
        caller.join(1_000);

        assertTrue(failure.get() instanceof UncheckedIOException);
        assertTrue(rpc.closed);
    }

    private static void assertProtocolViolation(JsonRpcMessage invalid) throws Exception {
        ManualConnection rpc = new ManualConnection();
        try (RpcClientConnection connection = new RpcClientConnection(rpc, JSON, ignored -> {})) {
            rpc.inbound.add(invalid);
            assertTrue(rpc.closedSignal.await(1, TimeUnit.SECONDS));
            assertThrows(UncheckedIOException.class, () -> connection.query("test/read", Map.of(), Result.class));
        }
    }

    private record Result(String value) {}

    private static final class ReceiveFailureConnection implements RpcConnection {
        private final IOException receiveFailure;
        private final IOException closeFailure;
        private final CountDownLatch closed = new CountDownLatch(1);

        private ReceiveFailureConnection(IOException receiveFailure, IOException closeFailure) {
            this.receiveFailure = receiveFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public void send(JsonRpcMessage message) {}

        @Override
        public JsonRpcMessage receive() throws IOException {
            throw receiveFailure;
        }

        @Override
        public void close() throws IOException {
            closed.countDown();
            throw closeFailure;
        }
    }

    private static final class ManualConnection implements RpcConnection {
        private static final Object CLOSED = new Object();

        private final BlockingQueue<Object> inbound = new ArrayBlockingQueue<>(4);
        private final CountDownLatch sent = new CountDownLatch(1);
        private final CountDownLatch closedSignal = new CountDownLatch(1);
        private volatile boolean closed;

        @Override
        public void send(JsonRpcMessage message) {
            sent.countDown();
        }

        @Override
        public JsonRpcMessage receive() throws IOException {
            try {
                Object message = inbound.take();
                if (message == CLOSED) {
                    throw new IOException("manual connection closed");
                }
                return (JsonRpcMessage) message;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("manual receive interrupted", interrupted);
            }
        }

        @Override
        public void close() {
            closed = true;
            closedSignal.countDown();
            inbound.clear();
            inbound.offer(CLOSED);
        }
    }
}
