package com.javaclaw.desktop;

import java.time.Clock;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CoreRpcContracts;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            connect(presenter);
            server.requestOverride = request -> {
                if (request.method().equals("turn/start")) {
                    throw new IllegalStateException("模型配置当前不可用");
                }
                return Optional.empty();
            };
            var result = presenter.send("失败后保留这条消息", ExecutionOverrides.empty());
            assertThrows(CompletionException.class, result::join);
            assertEquals(0, server.turnStarts.get());
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
            connect(presenter);
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
            var recovered =
                    presenter.send("仅发送一次", ExecutionOverrides.empty(), options).get(5, TimeUnit.SECONDS);
            assertEquals(DesktopTestFixtures.turn().id(), recovered.id());
            assertEquals(1, server.turnStarts.get());
            assertEquals(1, accepted.size());
        }
    }

    private static void connect(DesktopPresenter presenter) throws Exception {
        AtomicReference<DesktopState> state = new AtomicReference<>();
        presenter.subscribe(state::set);
        presenter.connect();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            DesktopState current = state.get();
            if (current.connection().status() == ConnectionState.Status.CONNECTED
                    && !current.interaction().busy()
                    && current.transcript().nextSequence() > 0) {
                return;
            }
            Thread.sleep(5);
        }
        assertTrue(false, "本地 SDK 夹具未能在限定时间内完成加载");
    }
}
