package com.javaclaw.server.rpc;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.ThreadExecutionIntent;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreItemCodecs;
import com.javaclaw.protocol.JsonRpcMessage;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.RpcConnection;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.server.TurnContractFixtures;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2TurnJournal;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.TurnStreamService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnStreamSessionTest {
    @TempDir
    Path directory;

    private final CanonicalJson json = new CanonicalJson();
    private H2Database database;
    private CoreCommandService core;
    private com.javaclaw.api.ThreadId threadId;
    private TurnStreamService streams;
    private TurnId turnId;

    @BeforeEach
    void 创建无模型的已取消Turn() {
        database = new H2Database(directory.resolve("data-v6"));
        database.initialize();
        core = new CoreCommandService(database, json, Clock.systemUTC());
        var workspace = core.createWorkspace(identity("workspace/create", "workspace"), "stream", directory);
        var thread = core.createThread(
                identity("thread/create", "thread"),
                workspace.id(),
                Optional.empty(),
                ThreadExecutionIntent.WORKSPACE,
                "stream");
        threadId = thread.id();
        var message = new CorePayloads.Message(MessageRole.USER, "你好", List.of(), Optional.empty());
        var request = TurnContractFixtures.request(
                thread.id(), new TurnBudget(100, 100, 0, 2, Duration.ofMinutes(1)), message);
        turnId = core.startTurn(identity("turn/start", "turn"), request).id();
        var journal = new H2TurnJournal(database, CoreItemCodecs.createRegistry(json), json, Clock.systemUTC());
        journal.transition(turnId, TurnStatus.QUEUED, TurnStatus.CANCELLED, Optional.empty());
        streams = new TurnStreamService(database, json);
    }

    @Test
    void 历史重放先于水位且重复受理不创建第二个drain() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        try (TurnStreamSession session = new TurnStreamSession(streams, connection, json)) {
            var request = new TurnStreamRpcContracts.Subscribe("sub", turnId, "START");
            CanonicalPayload command = command("key", 0, request);
            CanonicalPayload receipt = session.handle(TurnStreamRpcContracts.SUBSCRIBE, command);
            var data = connection.next(json);
            var watermark = connection.next(json);
            assertEquals(1, data.events().size());
            assertTrue(watermark.watermark().orElseThrow().terminal());
            assertEquals(
                    data.events().getLast().cursor(),
                    watermark.watermark().orElseThrow().lastCursor());
            assertEquals(receipt, session.handle(TurnStreamRpcContracts.SUBSCRIBE, command));
            assertEquals(receipt, session.handle(TurnStreamRpcContracts.SUBSCRIBE, command("other-key", 0, request)));
            assertNull(connection.messages.poll(150, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void 连接幂等拒绝改参且关闭ID不会被原命令重放复活() {
        RecordingConnection connection = new RecordingConnection();
        try (TurnStreamSession session = new TurnStreamSession(streams, connection, json)) {
            var request = new TurnStreamRpcContracts.Subscribe("sub", turnId, "START");
            CanonicalPayload original = command("key", 0, request);
            var receipt = session.handle(TurnStreamRpcContracts.SUBSCRIBE, original);
            assertThrows(
                    PersistenceException.class,
                    () -> session.handle(
                            TurnStreamRpcContracts.SUBSCRIBE,
                            command("key", 0, new TurnStreamRpcContracts.Subscribe("other", turnId, "START"))));
            var unsubscribe = command("close", 0, new TurnStreamRpcContracts.Unsubscribe("sub"));
            assertEquals(
                    session.handle(TurnStreamRpcContracts.UNSUBSCRIBE, unsubscribe),
                    session.handle(TurnStreamRpcContracts.UNSUBSCRIBE, unsubscribe));
            assertEquals(receipt, session.handle(TurnStreamRpcContracts.SUBSCRIBE, original));
            assertThrows(
                    PersistenceException.class,
                    () -> session.handle(TurnStreamRpcContracts.SUBSCRIBE, command("new-key", 0, request)));
            assertThrows(
                    PersistenceException.class,
                    () -> session.handle(TurnStreamRpcContracts.SUBSCRIBE, command("revision", 1, request)));
        }
    }

    @Test
    void 未知退订幂等且连接回执容量不能静默淘汰() {
        RecordingConnection connection = new RecordingConnection();
        try (TurnStreamSession session = new TurnStreamSession(streams, connection, json)) {
            for (int index = 0; index < 1024; index++) {
                session.handle(
                        TurnStreamRpcContracts.UNSUBSCRIBE,
                        command("close-" + index, 0, new TurnStreamRpcContracts.Unsubscribe("id-" + index)));
            }
            assertThrows(
                    PersistenceException.class,
                    () -> session.handle(
                            TurnStreamRpcContracts.UNSUBSCRIBE,
                            command("overflow", 0, new TurnStreamRpcContracts.Unsubscribe("other"))));
            assertNotNull(session.handle(
                    TurnStreamRpcContracts.UNSUBSCRIBE,
                    command("close-0", 0, new TurnStreamRpcContracts.Unsubscribe("id-0"))));
        }
    }

    @Test
    void 订阅空日志后提交的终态通过共享唤醒及时到达且仍先事件后水位() throws Exception {
        var message = new CorePayloads.Message(MessageRole.USER, "新Turn", List.of(), Optional.empty());
        TurnId live = core.startTurn(
                        identity("turn/start", "live"),
                        TurnContractFixtures.request(
                                threadId, new TurnBudget(100, 100, 0, 0, Duration.ofMinutes(1)), message))
                .id();
        var journal = new H2TurnJournal(
                database,
                CoreItemCodecs.createRegistry(json),
                json,
                Clock.systemUTC(),
                new com.javaclaw.server.persistence.LiveTurnBudgets(),
                streams);
        RecordingConnection connection = new RecordingConnection();
        try (TurnStreamSession session = new TurnStreamSession(streams, connection, json)) {
            session.handle(
                    TurnStreamRpcContracts.SUBSCRIBE,
                    command("live-sub", 0, new TurnStreamRpcContracts.Subscribe("live", live, "START")));
            assertTrue(connection.next(json).watermark().isPresent());
            journal.transition(live, TurnStatus.QUEUED, TurnStatus.CANCELLED, Optional.empty());
            var data = connection.next(json);
            assertEquals(1, data.events().size());
            var watermark = connection.next(json).watermark().orElseThrow();
            assertTrue(watermark.terminal());
            assertEquals(data.events().getLast().cursor(), watermark.lastCursor());
        }
    }

    @Test
    void 慢客户端阻塞发送时在硬截止关闭连接而不阻塞持久事务() throws Exception {
        BlockingConnection connection = new BlockingConnection();
        try (TurnStreamSession session = new TurnStreamSession(streams, connection, json)) {
            assertNotNull(session.handle(
                    TurnStreamRpcContracts.SUBSCRIBE,
                    command("slow", 0, new TurnStreamRpcContracts.Subscribe("slow", turnId, "START"))));
            assertTrue(connection.sending.await(2, TimeUnit.SECONDS));
            assertTrue(streams.watermark(turnId).terminal());
            assertTrue(connection.closed.await(6, TimeUnit.SECONDS));
        }
    }

    private CanonicalPayload command(String key, long revision, Object payload) {
        return json.encode(new WriteCommand(key, revision, json.encode(payload)));
    }

    private CommandIdentity identity(String method, String key) {
        return CommandIdentity.from(method, new WriteCommand(key, 0, json.parse("{}")), json);
    }

    private static final class BlockingConnection implements RpcConnection {
        private final CountDownLatch sending = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void send(JsonRpcMessage message) throws IOException {
            sending.countDown();
            try {
                if (!closed.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("test send deadline exceeded");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("connection closed");
        }

        @Override
        public JsonRpcMessage receive() throws IOException {
            throw new EOFException();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class RecordingConnection implements RpcConnection {
        private final ArrayBlockingQueue<JsonRpcNotification> messages = new ArrayBlockingQueue<>(64);

        @Override
        public void send(JsonRpcMessage message) throws IOException {
            if (!messages.offer((JsonRpcNotification) message)) {
                throw new IOException("test notification queue is full");
            }
        }

        @Override
        public JsonRpcMessage receive() throws IOException {
            throw new EOFException();
        }

        @Override
        public void close() {}

        TurnStreamRpcContracts.Notification next(CanonicalJson json) throws InterruptedException {
            var message = messages.poll(2, TimeUnit.SECONDS);
            assertNotNull(message);
            return json.decode(message.params(), TurnStreamRpcContracts.Notification.class);
        }
    }
}
