package com.javaclaw.desktop;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ItemEnvelope;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopSendResultTest {
    @Test
    void 服务端接受后返回原对话Turn供输入框确认清理草稿() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        try (DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, Clock.systemUTC())) {
            connect(presenter);
            var result = presenter.send("配置后直接发送", ExecutionOverrides.empty()).get(5, TimeUnit.SECONDS);
            assertEquals(server.thread().id(), result.threadId());
            assertEquals("配置后直接发送", server.lastTurnStart.message());
            assertEquals(1, server.turnStarts.get());
        }
    }

    @Test
    void 服务端拒绝返回失败而不伪装成功清除输入() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        try (DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, Clock.systemUTC())) {
            AtomicReference<DesktopState> state = connect(presenter);
            server.requestOverride = request -> {
                if (request.method().equals("turn/start")) {
                    throw new IllegalStateException("模型配置当前不可用");
                }
                return Optional.empty();
            };
            var result = presenter.send("失败后保留这条消息", ExecutionOverrides.empty());
            assertThrows(CompletionException.class, result::join);
            assertEquals(0, server.turnStarts.get());
            assertEquals(
                    OutgoingMessage.Status.UNCONFIRMED,
                    state.get().transcript().outgoing().orElseThrow().status());
            assertEquals(
                    "失败后保留这条消息",
                    state.get().transcript().outgoing().orElseThrow().text());
        }
    }

    @Test
    void 未连接时明确拒绝发送而不是静默吞掉消息() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        try (DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, Clock.systemUTC())) {
            var result = presenter.send("尚未连接", ExecutionOverrides.empty());
            assertThrows(CompletionException.class, result::join);
            assertEquals(0, server.turnStarts.get());
        }
    }

    @Test
    void 服务端已接受但回执丢失时同一次发送重试恢复原Turn() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        CanonicalJson json = new CanonicalJson();
        var accepted = new HashMap<String, CoreRpcContracts.TurnStartResult>();
        try (DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, Clock.systemUTC())) {
            AtomicReference<DesktopState> state = connect(presenter);
            server.requestOverride = request -> {
                if (!request.method().equals("turn/start")) {
                    return Optional.empty();
                }
                WriteCommand command = json.decode(request.params(), WriteCommand.class);
                var existing = accepted.get(command.idempotencyKey());
                if (existing != null) {
                    return Optional.of(existing);
                }
                var turn = DesktopTestFixtures.turn();
                accepted.put(
                        command.idempotencyKey(), new CoreRpcContracts.TurnStartResult(turn, turn.resolvedConfig()));
                server.turnStarts.incrementAndGet();
                throw new IllegalStateException("服务端已提交，首次回执丢失");
            };
            CommandOptions options = CommandOptions.create(0);
            var first = presenter.send("仅发送一次", ExecutionOverrides.empty(), options);
            assertThrows(CompletionException.class, first::join);
            String outgoingId =
                    state.get().transcript().outgoing().orElseThrow().id();
            long previousAttempt =
                    state.get().transcript().outgoing().orElseThrow().attempt();
            var recovered =
                    presenter.send("仅发送一次", ExecutionOverrides.empty(), options).get(5, TimeUnit.SECONDS);
            assertEquals(DesktopTestFixtures.turn().id(), recovered.id());
            assertEquals(1, server.turnStarts.get());
            assertEquals(1, accepted.size());
            assertEquals(
                    outgoingId,
                    state.get().transcript().outgoing().orElseThrow().id());
            assertTrue(state.get().transcript().outgoing().orElseThrow().attempt() > previousAttempt);
            assertEquals(
                    OutgoingMessage.Status.ACCEPTED,
                    state.get().transcript().outgoing().orElseThrow().status());
        }
    }

    @Test
    void 启动回执阻塞时用户正文已经回显且历史序号未提前推进() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        try (DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, Clock.systemUTC())) {
            AtomicReference<DesktopState> state = connect(presenter);
            long sequence = state.get().transcript().nextSequence();
            server.requestOverride = request -> {
                if (request.method().equals("turn/start")) {
                    entered.countDown();
                    awaitLatch(released);
                }
                return Optional.empty();
            };
            var result = presenter.send("发送后立刻显示", ExecutionOverrides.empty());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            assertTrue(state.get().interaction().busy());
            assertEquals(sequence, state.get().transcript().nextSequence());
            assertEquals(
                    "发送后立刻显示", state.get().transcript().outgoing().orElseThrow().text());
            assertEquals(
                    OutgoingMessage.Status.SENDING,
                    state.get().transcript().outgoing().orElseThrow().status());
            released.countDown();
            result.get(5, TimeUnit.SECONDS);
        } finally {
            released.countDown();
        }
    }

    @Test
    void 幂等恢复直接返回终态时仍补齐权威历史并替换用户回显() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        try (DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, Clock.systemUTC())) {
            AtomicReference<DesktopState> state = connect(presenter);
            var completed = DesktopTestFixtures.turn(server.thread(), TurnStatus.COMPLETED, 2);
            var message = new ItemEnvelope(
                    ItemId.random(),
                    completed.id(),
                    2,
                    "message",
                    CoreSchemas.MESSAGE,
                    "core",
                    ItemStatus.COMPLETED,
                    new CanonicalJson()
                            .encode(new CorePayloads.Message(MessageRole.USER, "权威已提交消息", List.of(), Optional.empty())),
                    completed.createdAt(),
                    Optional.of(completed.updatedAt()));
            server.requestOverride = request -> switch (request.method()) {
                case "turn/start" ->
                    Optional.of(new CoreRpcContracts.TurnStartResult(completed, completed.resolvedConfig()));
                case "turn/read" -> Optional.of(completed);
                case "item/list" -> Optional.of(new CoreRpcContracts.ItemListResult(List.of(message), 2));
                default -> Optional.empty();
            };
            presenter.send("回执丢失后重试的完整正文", ExecutionOverrides.empty()).get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline
                    && (state.get().transcript().nextSequence() != 2
                            || state.get().interaction().busy())) {
                Thread.sleep(5);
            }
            assertEquals(List.of(message), state.get().transcript().items());
            assertTrue(state.get().transcript().outgoing().isEmpty());
            assertFalse(state.get().interaction().busy());
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("发送测试闸门未及时释放");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static AtomicReference<DesktopState> connect(DesktopPresenter presenter) throws Exception {
        AtomicReference<DesktopState> state = new AtomicReference<>();
        presenter.subscribe(state::set);
        presenter.connect();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            DesktopState current = state.get();
            if (current.connection().status() == ConnectionState.Status.CONNECTED
                    && !current.interaction().busy()
                    && current.transcript().nextSequence() > 0) {
                return state;
            }
            Thread.sleep(5);
        }
        assertTrue(false, "本地 SDK 夹具未能在限定时间内完成加载");
        return state;
    }
}
