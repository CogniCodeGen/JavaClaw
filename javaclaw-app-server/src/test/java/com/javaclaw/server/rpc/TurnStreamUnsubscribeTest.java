package com.javaclaw.server.rpc;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.JsonRpcCodec;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.StreamRpcConnection;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.TurnStreamService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.MAC, OS.LINUX})
class TurnStreamUnsubscribeTest {
    @TempDir
    Path directory;

    private final CanonicalJson json = new CanonicalJson();
    private TurnStreamService streams;
    private ThreadId threadId;
    private TurnId turnId;

    @BeforeEach
    void 创建无需模型的终态日志() {
        H2Database database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        var core = new CoreCommandService(database, json, Clock.systemUTC());
        var workspace = core.createWorkspace(identity("workspace/create"), "stream", directory);
        var thread = core.createThread(
                identity("thread/create"), workspace.id(), Optional.empty(), ThreadExecutionIntent.WORKSPACE, "stream");
        threadId = thread.id();
        var message = new CorePayloads.Message(MessageRole.USER, "你好", List.of(), Optional.empty());
        turnId = core.startTurn(
                        identity("turn/start"),
                        TurnContractFixtures.request(
                                threadId, new TurnBudget(100, 100, 0, 2, Duration.ofMinutes(1)), message))
                .id();
        var journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, Clock.systemUTC());
        journal.transition(turnId, TurnStatus.QUEUED, TurnStatus.CANCELLED, Optional.empty());
        streams = new TurnStreamService(database, json);
    }

    @Test
    void 通知即将写入真实Uds时退订仍可使用同连接查询() throws Exception {
        try (ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            var address = UnixDomainSocketAddress.of(directory.resolve("rpc.sock"));
            listener.bind(address);
            try (SocketChannel clientChannel = SocketChannel.open(address);
                    SocketChannel serverChannel = listener.accept();
                    StreamRpcConnection client = connection(clientChannel);
                    GatedConnection server = new GatedConnection(connection(serverChannel));
                    TurnStreamSession session = new TurnStreamSession(streams, server, json)) {
                subscribe(session);
                assertTrue(server.sending.await(2, TimeUnit.SECONDS));
                unsubscribe(session);
                server.proceed.countDown();
                assertTrue(server.sent.await(2, TimeUnit.SECONDS));
                assertNull(server.failure.get(), "释放单个订阅不能中断并关闭借用的共享 SocketChannel");
                assertTrue(serverChannel.isOpen());
                assertInstanceOf(JsonRpcNotification.class, client.receive());

                var request = new JsonRpcRequest(
                        new RpcId("history"),
                        TurnStreamRpcContracts.ITEM_HISTORY,
                        json.encode(new TurnStreamRpcContracts.ItemHistoryRequest(threadId, 0, 100)));
                client.send(request);
                var received = assertInstanceOf(JsonRpcRequest.class, server.receive());
                server.send(
                        JsonRpcResponse.success(received.id(), session.handle(received.method(), received.params())));
                var response = assertInstanceOf(JsonRpcResponse.class, client.receive());
                assertEquals(request.id(), response.id());
                assertTrue(response.result().isPresent());
                server.worker.get().join(Duration.ofSeconds(2));
                assertFalse(server.worker.get().isAlive());
                assertEquals(1, server.notifications.get(), "退订后不得继续发送水位或下一页通知");
            }
        }
    }

    @Test
    void 空闲订阅退订后立即唤醒并结束且不再发送通知() throws Exception {
        IdleConnection connection = new IdleConnection();
        try (TurnStreamSession session = new TurnStreamSession(streams, connection, json)) {
            subscribe(session);
            assertTrue(connection.watermark.await(2, TimeUnit.SECONDS));
            unsubscribe(session);
            connection.worker.get().join(Duration.ofSeconds(2));
            assertFalse(connection.worker.get().isAlive());
            assertEquals(2, connection.notifications.get());
            assertFalse(connection.closed);
        }
    }

    private StreamRpcConnection connection(SocketChannel channel) {
        return new StreamRpcConnection(
                Channels.newInputStream(channel), Channels.newOutputStream(channel), new JsonRpcCodec(json));
    }

    private void subscribe(TurnStreamSession session) {
        session.handle(
                TurnStreamRpcContracts.SUBSCRIBE,
                json.encode(new WriteCommand(
                        "subscribe", 0, json.encode(new TurnStreamRpcContracts.Subscribe("sub", turnId, "START")))));
    }

    private void unsubscribe(TurnStreamSession session) {
        session.handle(
                TurnStreamRpcContracts.UNSUBSCRIBE,
                json.encode(new WriteCommand(
                        "unsubscribe", 0, json.encode(new TurnStreamRpcContracts.Unsubscribe("sub")))));
    }

    private CommandIdentity identity(String method) {
        return CommandIdentity.from(method, new WriteCommand(method, 0, json.parse("{}")), json);
    }

    private static final class GatedConnection implements RpcConnection {
        private final StreamRpcConnection delegate;
        private final CountDownLatch sending = new CountDownLatch(1);
        private final CountDownLatch proceed = new CountDownLatch(1);
        private final CountDownLatch sent = new CountDownLatch(1);
        private final AtomicReference<IOException> failure = new AtomicReference<>();
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private final AtomicInteger notifications = new AtomicInteger();

        private GatedConnection(StreamRpcConnection delegate) {
            this.delegate = delegate;
        }

        @Override
        public void send(JsonRpcMessage message) throws IOException {
            if (!(message instanceof JsonRpcNotification)) {
                delegate.send(message);
                return;
            }
            worker.set(Thread.currentThread());
            notifications.incrementAndGet();
            sending.countDown();
            // 锁住进入真实 channel.write 前的窗口；恢复中断标志，让 NIO 自己决定是否关闭共享通道。
            boolean interrupted = false;
            while (proceed.getCount() != 0) {
                try {
                    proceed.await();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            try {
                delegate.send(message);
            } catch (IOException problem) {
                failure.set(problem);
                throw problem;
            } finally {
                sent.countDown();
            }
        }

        @Override
        public JsonRpcMessage receive() throws IOException {
            return delegate.receive();
        }

        @Override
        public void close() throws IOException {
            proceed.countDown();
            delegate.close();
        }
    }

    private static final class IdleConnection implements RpcConnection {
        private final CountDownLatch watermark = new CountDownLatch(1);
        private final AtomicReference<Thread> worker = new AtomicReference<>();
        private final AtomicInteger notifications = new AtomicInteger();
        private volatile boolean closed;

        @Override
        public void send(JsonRpcMessage message) {
            worker.set(Thread.currentThread());
            if (notifications.incrementAndGet() == 2) {
                watermark.countDown();
            }
        }

        @Override
        public JsonRpcMessage receive() {
            throw new UnsupportedOperationException("该夹具只记录订阅通知");
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
