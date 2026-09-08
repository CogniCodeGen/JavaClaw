package com.javaclaw.client.facade;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ItemId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.TurnStreamCall;
import com.javaclaw.api.TurnStreamEvent;
import com.javaclaw.api.TurnStreamKind;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.JsonRpcNotification;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.NegotiatedCapabilities;
import com.javaclaw.protocol.TurnStreamRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnStreamClientTest {
    private final CanonicalJson json = new CanonicalJson();
    private final TurnId turnId = TurnId.random();
    private final TurnStreamCall call = new TurnStreamCall(1, "attempt", ItemId.random());

    @Test
    void 回执前通知不会丢失且断线主动报告最后已应用快照() throws Exception {
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<ScriptedRpcConnection> wire = new AtomicReference<>();
        wire.set(new ScriptedRpcConnection(request -> subscribeResponse(
                request,
                wire.get(),
                List.of(
                        event("1", "START", TurnStreamKind.STARTED, "", 0),
                        event("4", "1", TurnStreamKind.TEXT_DELTA, "实时正文", 0)))));
        try (RpcClientConnection connection = new RpcClientConnection(wire.get(), json, ignored -> {});
                TurnStreamClient client = new TurnStreamClient(connection, capabilities());
                TurnStreamSubscription subscription =
                        client.subscribe(turnId, snapshot -> updated.countDown(), failure -> failed.countDown())) {
            assertTrue(updated.await(2, TimeUnit.SECONDS));
            assertEquals("实时正文", subscription.snapshot().messages().getFirst().text());
            wire.get().close();
            assertTrue(failed.await(2, TimeUnit.SECONDS));
            assertEquals("4", subscription.snapshot().cursor());
        }
    }

    @Test
    void 前驱缺口在协调线程从已应用cursor重订阅而不阻塞reader() throws Exception {
        CountDownLatch updated = new CountDownLatch(1);
        AtomicInteger subscriptions = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ScriptedRpcConnection> wire = new AtomicReference<>();
        wire.set(new ScriptedRpcConnection(request -> {
            if (!request.method().equals(TurnStreamRpcContracts.SUBSCRIBE)) {
                return released(request);
            }
            var command = json.decode(request.params(), WriteCommand.class);
            var parameters = json.decode(command.payload(), TurnStreamRpcContracts.Subscribe.class);
            int attempt = subscriptions.incrementAndGet();
            if (attempt == 1) {
                return subscribeResponse(
                        request,
                        wire.get(),
                        List.of(
                                event("1", "START", TurnStreamKind.STARTED, "", 0),
                                event("4", "missing", TurnStreamKind.TEXT_DELTA, "不可应用", 0)));
            }
            assertEquals("1", parameters.afterCursor());
            return subscribeResponse(request, wire.get(), List.of(event("3", "1", TurnStreamKind.TEXT_DELTA, "补回", 0)));
        }));
        try (RpcClientConnection connection = new RpcClientConnection(wire.get(), json, ignored -> {});
                TurnStreamClient client = new TurnStreamClient(connection, capabilities());
                TurnStreamSubscription subscription = client.subscribe(
                        turnId,
                        snapshot -> {
                            if (snapshot.cursor().equals("3")) {
                                updated.countDown();
                            }
                        },
                        failure::set)) {
            assertTrue(updated.await(3, TimeUnit.SECONDS));
            assertEquals(2, subscriptions.get());
            assertEquals("补回", subscription.snapshot().messages().getFirst().text());
            assertEquals(null, failure.get());
        }
    }

    @Test
    void 新连接resume发送旧Java游标且保留之前正文() throws Exception {
        var previous = new TurnStreamSnapshot(
                turnId,
                "4",
                List.of(new TurnStreamSnapshot.Message(call, "此前", TurnStreamKind.TEXT_DELTA, Optional.empty())),
                Optional.empty(),
                Optional.empty());
        CountDownLatch updated = new CountDownLatch(1);
        AtomicReference<ScriptedRpcConnection> wire = new AtomicReference<>();
        wire.set(new ScriptedRpcConnection(request -> {
            if (request.method().equals(TurnStreamRpcContracts.SUBSCRIBE)) {
                var command = json.decode(request.params(), WriteCommand.class);
                var parameters = json.decode(command.payload(), TurnStreamRpcContracts.Subscribe.class);
                assertEquals("4", parameters.afterCursor());
            }
            return subscribeResponse(request, wire.get(), List.of(event("8", "4", TurnStreamKind.TEXT_DELTA, "继续", 2)));
        }));
        try (RpcClientConnection connection = new RpcClientConnection(wire.get(), json, ignored -> {});
                TurnStreamClient client = new TurnStreamClient(connection, capabilities());
                TurnStreamSubscription subscription =
                        client.resume(previous, snapshot -> updated.countDown(), ignored -> {})) {
            assertTrue(updated.await(2, TimeUnit.SECONDS));
            assertEquals("此前继续", subscription.snapshot().messages().getFirst().text());
        }
    }

    @Test
    void 慢消费者超过事件上限从已应用游标恢复且关闭facade拒绝新订阅() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch restored = new CountDownLatch(1);
        AtomicReference<String> id = new AtomicReference<>();
        AtomicReference<ScriptedRpcConnection> wire = new AtomicReference<>();
        wire.set(new ScriptedRpcConnection(request -> overflowResponse(request, wire.get(), id)));
        try (RpcClientConnection connection = new RpcClientConnection(wire.get(), json, ignored -> {});
                TurnStreamClient client = new TurnStreamClient(connection, capabilities());
                TurnStreamSubscription subscription = client.subscribe(
                        turnId,
                        value -> {
                            if (value.cursor().equals("1")) {
                                entered.countDown();
                                await(release);
                            } else if (value.cursor().equals("replayed")) {
                                restored.countDown();
                            }
                        },
                        ignored -> {})) {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < 257; index++) {
                var notification = new TurnStreamRpcContracts.Notification(
                        id.get(),
                        List.of(event("queued-" + index, "unused", TurnStreamKind.TEXT_DELTA, "未应用", 0)),
                        Optional.empty());
                subscription.offer(notification, 100);
            }
            assertEquals("1", subscription.snapshot().cursor());
            release.countDown();
            assertTrue(restored.await(3, TimeUnit.SECONDS));
            assertEquals("恢复正文", subscription.snapshot().messages().getFirst().text());
            client.close();
            assertThrows(IllegalStateException.class, () -> client.subscribe(turnId, ignored -> {}, ignored -> {}));
        } finally {
            release.countDown();
        }
    }

    private JsonRpcResponse overflowResponse(
            JsonRpcRequest request, ScriptedRpcConnection wire, AtomicReference<String> id) {
        if (!request.method().equals(TurnStreamRpcContracts.SUBSCRIBE)) {
            return released(request);
        }
        var command = json.decode(request.params(), WriteCommand.class);
        var parameters = json.decode(command.payload(), TurnStreamRpcContracts.Subscribe.class);
        boolean first = id.getAndSet(parameters.subscriptionId()) == null;
        assertEquals(first ? "START" : "1", parameters.afterCursor());
        return subscribeResponse(
                request,
                wire,
                List.of(
                        first
                                ? event("1", "START", TurnStreamKind.STARTED, "", 0)
                                : event("replayed", "1", TurnStreamKind.TEXT_DELTA, "恢复正文", 0)));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private JsonRpcResponse subscribeResponse(
            JsonRpcRequest request, ScriptedRpcConnection wire, List<TurnStreamEvent> events) {
        if (!request.method().equals(TurnStreamRpcContracts.SUBSCRIBE)) {
            return released(request);
        }
        var command = json.decode(request.params(), WriteCommand.class);
        var parameters = json.decode(command.payload(), TurnStreamRpcContracts.Subscribe.class);
        try {
            wire.emit(new JsonRpcNotification(
                    TurnStreamRpcContracts.EVENT,
                    json.encode(new TurnStreamRpcContracts.Notification(
                            parameters.subscriptionId(), events, Optional.empty()))));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        return JsonRpcResponse.success(
                request.id(),
                json.encode(new TurnStreamRpcContracts.Receipt(
                        parameters.subscriptionId(), parameters.turnId(), parameters.afterCursor())));
    }

    private JsonRpcResponse released(JsonRpcRequest request) {
        var command = json.decode(request.params(), WriteCommand.class);
        var payload = json.decode(command.payload(), TurnStreamRpcContracts.Unsubscribe.class);
        return JsonRpcResponse.success(
                request.id(), json.encode(new TurnStreamRpcContracts.Released(payload.subscriptionId())));
    }

    private TurnStreamEvent event(String cursor, String previous, TurnStreamKind kind, String text, long offset) {
        return new TurnStreamEvent(
                turnId,
                cursor,
                previous,
                new TurnStreamEvent.Data(kind, Optional.of(call), text, offset, Optional.empty(), Optional.empty()));
    }

    private static NegotiatedCapabilities capabilities() {
        return new NegotiatedCapabilities(Set.of(TurnStreamRpcContracts.CAPABILITY), Set.of());
    }
}
