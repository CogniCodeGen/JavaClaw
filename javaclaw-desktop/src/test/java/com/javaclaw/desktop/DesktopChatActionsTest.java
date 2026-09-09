package com.javaclaw.desktop;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopChatActionsTest {
    private static final ProviderRef MODEL = new ProviderRef("configured", 3, "model");

    @Test
    void 模型已用于当前对话但记忆失败仍投递成功阶段通知() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var presenter = presenter(fixture)) {
            connected(presenter);
            fixture.failRecentSave = true;
            List<DesktopConfigurationChange> changes = new CopyOnWriteArrayList<>();
            try (var subscription = presenter.configurationEvents().subscribe(changes::add)) {
                ExecutionException failure = assertThrows(
                        ExecutionException.class,
                        () -> presenter
                                .useModel(
                                        Optional.of(fixture.server.workspace().id()),
                                        Optional.of(fixture.server.thread().id()),
                                        MODEL)
                                .toCompletableFuture()
                                .get(5, TimeUnit.SECONDS));

                assertTrue(failure.getCause().getMessage().contains("模型已用于当前对话"));
                assertEquals(1, changes.size());
                assertEquals(
                        Optional.of(fixture.server.thread().id()),
                        changes.getFirst().threadId());
                assertEquals(List.of("thread/execution/update"), fixture.mutations);
            }
        }
    }

    @Test
    void 日常默认成功但对话保存失败说明部分状态且允许原版本重试() throws Exception {
        try (var fixture = new ModelSelectionRpcFixture();
                var presenter = presenter(fixture)) {
            connected(presenter);
            fixture.failThreadSave = true;
            ExecutionOverrides selected =
                    DesktopModelPreferences.replace(ExecutionOverrides.empty(), Optional.of(MODEL), Optional.empty());
            var thread = fixture.server.thread();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> presenter
                            .rememberChatSelection(
                                    thread.workspaceId(), thread.id(), selected, CommandOptions.create(0), true)
                            .toCompletableFuture()
                            .get(5, TimeUnit.SECONDS));

            assertTrue(failure.getCause().getMessage().contains("日常默认已保存"));
            var result = presenter
                    .rememberChatSelection(thread.workspaceId(), thread.id(), selected, CommandOptions.create(0), true)
                    .toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertEquals(selected, result.overrides());
            assertTrue(fixture.defaults.isEmpty());
            assertEquals(selected, fixture.recent.orElseThrow().overrides());
            assertEquals(List.of("execution/recent/update", "thread/execution/update"), fixture.mutations);
        }
    }

    private static DesktopPresenter presenter(ModelSelectionRpcFixture fixture) {
        return new DesktopPresenter(
                fixture.server::client, Runnable::run, Clock.fixed(DesktopTestFixtures.NOW, ZoneOffset.UTC));
    }

    private static void connected(DesktopPresenter presenter) throws Exception {
        AtomicReference<DesktopState> state = new AtomicReference<>();
        presenter.subscribe(state::set);
        presenter.connect();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((state.get().connection().status() != ConnectionState.Status.CONNECTED
                        || state.get().threads().selectedThread().isEmpty())
                && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        assertEquals(ConnectionState.Status.CONNECTED, state.get().connection().status());
    }
}
