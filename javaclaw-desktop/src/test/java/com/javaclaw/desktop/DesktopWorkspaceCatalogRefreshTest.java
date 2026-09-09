package com.javaclaw.desktop;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.protocol.CoreRpcContracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopWorkspaceCatalogRefreshTest {
    @Test
    void 重命名和归档自动更新目录且保留当前对话() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        AtomicReference<List<Workspace>> catalog = new AtomicReference<>(List.of(server.workspace()));
        server.requestOverride = request -> request.method().equals("workspace/list")
                ? Optional.of(new CoreRpcContracts.WorkspaceListResult(catalog.get()))
                : Optional.empty();
        try (DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, Clock.systemUTC())) {
            AtomicReference<DesktopState> state = connect(presenter);
            Workspace renamed = updated(server.workspace(), "新项目名称", WorkspaceLifecycle.ACTIVE, 2);
            catalog.set(List.of(renamed));
            changed(presenter, renamed);
            await(() -> state.get()
                    .threads()
                    .selectedWorkspace()
                    .filter(renamed::equals)
                    .isPresent());
            assertEquals(List.of(renamed), state.get().threads().workspaces());
            Workspace archived = updated(renamed, renamed.name(), WorkspaceLifecycle.ARCHIVED, 3);
            catalog.set(List.of(archived));
            changed(presenter, archived);
            await(() -> state.get()
                    .threads()
                    .selectedWorkspace()
                    .filter(archived::equals)
                    .isPresent());
            assertEquals(server.thread(), state.get().threads().selectedThread().orElseThrow());
            assertEquals(List.of(archived), state.get().threads().workspaces());
            assertEquals(archived, state.get().threads().selectedWorkspace().orElseThrow());
        }
    }

    @Test
    void 在途目录刷新收到连续变更后补读最新版本() throws Exception {
        PresenterRpcServer server = new PresenterRpcServer();
        try (DesktopPresenter presenter = new DesktopPresenter(server::client, Runnable::run, Clock.systemUTC())) {
            AtomicReference<DesktopState> state = connect(presenter);
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger reads = new AtomicInteger();
            Workspace latest = updated(server.workspace(), "连续更新后的名称", WorkspaceLifecycle.ACTIVE, 4);
            server.requestOverride = request -> {
                if (!request.method().equals("workspace/list")) {
                    return Optional.empty();
                }
                if (reads.incrementAndGet() == 1) {
                    reading.countDown();
                    awaitLatch(release);
                    return Optional.of(new CoreRpcContracts.WorkspaceListResult(List.of(server.workspace())));
                }
                return Optional.of(new CoreRpcContracts.WorkspaceListResult(List.of(latest)));
            };
            changed(presenter, latest);
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            changed(presenter, latest);
            changed(presenter, latest);
            release.countDown();
            await(() -> state.get()
                    .threads()
                    .selectedWorkspace()
                    .filter(latest::equals)
                    .isPresent());
            assertEquals(2, reads.get());
            assertEquals(server.thread(), state.get().threads().selectedThread().orElseThrow());
        }
    }

    private static AtomicReference<DesktopState> connect(DesktopPresenter presenter) throws Exception {
        AtomicReference<DesktopState> state = new AtomicReference<>();
        presenter.subscribe(state::set);
        presenter.connect();
        await(() -> state.get().connection().status() == ConnectionState.Status.CONNECTED
                && !state.get().interaction().busy()
                && state.get().transcript().nextSequence() > 0);
        return state;
    }

    private static void changed(DesktopPresenter presenter, Workspace workspace) {
        presenter
                .configurationEvents()
                .publish(new DesktopConfigurationChange(
                        DesktopConfigurationChange.Kind.WORKSPACES, Optional.of(workspace.id()), Optional.empty()));
    }

    private static Workspace updated(Workspace before, String name, WorkspaceLifecycle lifecycle, long revision) {
        return new Workspace(
                before.id(), name, before.root(), lifecycle, revision, before.createdAt(), before.updatedAt());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "本地目录状态未在限定时间内更新");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
