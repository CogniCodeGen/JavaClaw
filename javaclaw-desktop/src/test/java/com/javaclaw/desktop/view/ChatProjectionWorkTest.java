package com.javaclaw.desktop.view;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Rendered;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Scope;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Snapshot;
import com.javaclaw.desktop.web.WebSurfaceContent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatProjectionWorkTest {
    @Test
    void 清空同时撤销在途和已完成结果且同一实例可以继续显示() {
        ManualWorker worker = new ManualWorker();
        ArrayDeque<Runnable> ui = new ArrayDeque<>();
        List<Long> applied = new ArrayList<>();
        AtomicReference<ChatProjectionWork> owner = new AtomicReference<>();
        Scope scope = new Scope("thread", WorkspaceId.random());
        try (var work = new ChatProjectionWork(
                worker,
                ui::add,
                (source, current) -> {
                    if (source.version() == 2) {
                        owner.get().clear();
                        assertFalse(current.getAsBoolean());
                    }
                    return result(source);
                },
                value -> applied.add(value.version()),
                () -> {})) {
            owner.set(work);
            work.submit(snapshot(scope, 1));
            worker.runNext();
            work.clear();
            ui.removeFirst().run();
            assertTrue(applied.isEmpty());
            assertFalse(work.matchesScope(scope));
            work.submit(snapshot(scope, 2));
            worker.runNext();
            assertTrue(ui.isEmpty());
            work.submit(snapshot(scope, 3));
            worker.runNext();
            ui.removeFirst().run();
            assertEquals(List.of(3L), applied);
        }
    }

    @Test
    void 后台与UI分别仅保留一个任务且迟到同对话结果不能覆盖最新版本() {
        ManualWorker worker = new ManualWorker();
        ArrayDeque<Runnable> ui = new ArrayDeque<>();
        List<Long> rendered = new ArrayList<>();
        List<Long> applied = new ArrayList<>();
        Scope scope = new Scope("thread", WorkspaceId.random());
        try (var work = new ChatProjectionWork(
                worker,
                ui::add,
                (source, current) -> {
                    rendered.add(source.version());
                    return result(source);
                },
                value -> applied.add(value.version()),
                () -> {})) {
            work.submit(snapshot(scope, 1));
            work.submit(snapshot(scope, 2));
            assertEquals(1, worker.tasks.size());
            worker.runNext();
            work.submit(snapshot(scope, 3));
            worker.runNext();
            assertEquals(1, ui.size());
            work.submit(snapshot(scope, 4));
            ui.removeFirst().run();
            assertTrue(applied.isEmpty());
            worker.runNext();
            ui.removeFirst().run();
            assertEquals(List.of(2L, 3L, 4L), rendered);
            assertEquals(List.of(4L), applied);
        }
    }

    @Test
    void 运行中的旧投影收到新请求后失效且旧失败不会切换简版() {
        ManualWorker worker = new ManualWorker();
        ArrayDeque<Runnable> ui = new ArrayDeque<>();
        List<Long> applied = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<ChatProjectionWork> owner = new AtomicReference<>();
        Scope scope = new Scope("thread", WorkspaceId.random());
        try (var work = new ChatProjectionWork(
                worker,
                ui::add,
                (source, current) -> {
                    if (source.version() == 1) {
                        owner.get().submit(snapshot(scope, 2));
                        assertFalse(current.getAsBoolean());
                        throw new IllegalArgumentException("旧投影失败");
                    }
                    assertTrue(current.getAsBoolean());
                    return result(source);
                },
                value -> applied.add(value.version()),
                failures::incrementAndGet)) {
            owner.set(work);
            work.submit(snapshot(scope, 1));
            worker.runNext();
            assertEquals(1, ui.size());
            ui.removeFirst().run();
            assertEquals(List.of(2L), applied);
            assertEquals(0, failures.get());
        }
    }

    @Test
    void 排队失败被新成功取代而当前失败只通知一次() {
        ManualWorker worker = new ManualWorker();
        ArrayDeque<Runnable> ui = new ArrayDeque<>();
        List<Long> applied = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        Scope scope = new Scope("thread", WorkspaceId.random());
        try (var work = new ChatProjectionWork(
                worker,
                ui::add,
                (source, current) -> {
                    if (source.version() % 2 == 1) {
                        throw new IllegalArgumentException("投影失败");
                    }
                    return result(source);
                },
                value -> applied.add(value.version()),
                failures::incrementAndGet)) {
            work.submit(snapshot(scope, 1));
            worker.runNext();
            work.submit(snapshot(scope, 2));
            worker.runNext();
            assertEquals(1, ui.size());
            ui.removeFirst().run();
            assertEquals(List.of(2L), applied);
            assertEquals(0, failures.get());
            work.submit(snapshot(scope, 3));
            worker.runNext();
            ui.removeFirst().run();
            assertEquals(1, failures.get());
        }
    }

    @Test
    void 同名空对话切换工作区立即撤销旧作用域且关闭后排队回复无效() {
        ManualWorker worker = new ManualWorker();
        ArrayDeque<Runnable> ui = new ArrayDeque<>();
        List<Long> applied = new ArrayList<>();
        Scope first = new Scope("empty", WorkspaceId.random());
        Scope second = new Scope("empty", WorkspaceId.random());
        var work = new ChatProjectionWork(
                worker, ui::add, (source, current) -> result(source), value -> applied.add(value.version()), () -> {});
        work.submit(snapshot(first, 1));
        worker.runNext();
        assertTrue(work.matchesScope(first));
        work.submit(snapshot(second, 2));
        assertFalse(work.matchesScope(first));
        assertTrue(work.matchesScope(second));
        ui.removeFirst().run();
        assertTrue(applied.isEmpty());
        worker.runNext();
        work.close();
        ui.removeFirst().run();
        work.submit(snapshot(second, 3));
        assertFalse(work.matchesScope(second));
        assertTrue(applied.isEmpty());
        assertTrue(worker.tasks.isEmpty());
        assertTrue(worker.isShutdown());
    }

    private static Snapshot snapshot(Scope scope, long version) {
        return new Snapshot(scope, List.of(), List.of(), List.of(), false, version);
    }

    private static Rendered result(Snapshot snapshot) {
        return new Rendered(
                snapshot.scope(), snapshot.version(), WebSurfaceContent.serialized("{}"), Map.of(), Map.of());
    }

    /** 确定性推进后台队列，测试无需阻塞 JavaFX 或依赖线程调度速度。 */
    private static final class ManualWorker extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        private void runNext() {
            tasks.removeFirst().run();
        }

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> dropped = List.copyOf(tasks);
            tasks.clear();
            return dropped;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
