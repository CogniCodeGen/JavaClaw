package com.javaclaw.desktop.view;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.javaclaw.desktop.view.ChatProjectionRenderer.Rendered;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Scope;
import com.javaclaw.desktop.view.ChatProjectionRenderer.Snapshot;

/**
 * 聊天后台与 FX 提交之间的双单槽队列；只有最新请求的完整投影或失败可以交给 UI。
 *
 * <p>worker 和 UI 各至多排一个 drain。请求身份检查与 UI 提交共用锁，防止同对话新请求到达后旧结果更新引用表； 重投影在行边界检查失效，不中断现有 WebSurfaceHost 的 50ms 和确认策略。
 */
final class ChatProjectionWork implements AutoCloseable {
    private final ExecutorService worker;
    private final Consumer<Runnable> dispatch;
    private final BiFunction<Snapshot, BooleanSupplier, Rendered> render;
    private final Consumer<Rendered> apply;
    private final Runnable failure;
    private Snapshot latest;
    private Snapshot pending;
    private Outcome completed;
    private boolean running;
    private boolean scheduled;
    private boolean closed;

    ChatProjectionWork(
            ExecutorService worker,
            Consumer<Runnable> dispatch,
            BiFunction<Snapshot, BooleanSupplier, Rendered> render,
            Consumer<Rendered> apply,
            Runnable failure) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
        this.render = Objects.requireNonNull(render, "render");
        this.apply = Objects.requireNonNull(apply, "apply");
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    synchronized void submit(Snapshot snapshot) {
        if (closed) {
            return;
        }
        latest = Objects.requireNonNull(snapshot, "snapshot");
        pending = snapshot;
        if (!running) {
            running = true;
            worker.execute(this::drain);
        }
    }

    synchronized boolean matchesScope(Scope scope) {
        return !closed && latest != null && latest.scope().equals(scope);
    }

    synchronized void clear() {
        latest = null;
        pending = null;
        completed = null;
    }

    private void drain() {
        Snapshot snapshot;
        while ((snapshot = take()) != null) {
            Snapshot selected = snapshot;
            try {
                Rendered rendered = render.apply(selected, () -> current(selected));
                finish(new Outcome(selected, Optional.of(rendered)));
            } catch (RuntimeException invalid) {
                finish(new Outcome(selected, Optional.empty()));
            }
        }
    }

    private synchronized Snapshot take() {
        Snapshot selected = pending;
        pending = null;
        if (closed || selected == null) {
            running = false;
            return null;
        }
        return selected;
    }

    private synchronized boolean current(Snapshot snapshot) {
        return !closed && latest == snapshot;
    }

    private void finish(Outcome outcome) {
        boolean enqueue;
        synchronized (this) {
            if (!current(outcome.source())) {
                return;
            }
            completed = outcome;
            enqueue = !scheduled;
            scheduled = true;
        }
        if (enqueue) {
            dispatch.accept(this::deliver);
        }
    }

    private synchronized void deliver() {
        scheduled = false;
        Outcome selected = completed;
        completed = null;
        if (selected == null || !current(selected.source())) {
            return;
        }
        if (selected.rendered().isPresent()) {
            apply.accept(selected.rendered().orElseThrow());
        } else {
            failure.run();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        latest = null;
        pending = null;
        completed = null;
        worker.shutdownNow();
    }

    /** source 为非空请求身份；rendered 非空，Optional 为空表示该请求转换失败。 */
    private record Outcome(Snapshot source, Optional<Rendered> rendered) {}
}
