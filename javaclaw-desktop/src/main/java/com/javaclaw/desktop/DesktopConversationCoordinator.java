package com.javaclaw.desktop;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.Workspace;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.TranscriptState;
import com.javaclaw.protocol.CoreRpcContracts;

/**
 * 当前聊天页面的导航、读取及 Turn 操作归属，不拥有服务端任务的生命周期。
 *
 * <p>选择和 busy 在同一次 UI 提交中改变。后台工作冻结导航代次与 SDK 实例，回到 UI 后再次核验 Thread； 切换只释放展示订阅，不取消任务。迟到的启动回执不重放，也不写入新页面；返回时从持久历史恢复该 Turn。
 */
final class DesktopConversationCoordinator implements AutoCloseable {
    private final DesktopStore store;
    private final Consumer<Runnable> ui;
    private final ExecutorService workers;
    private final DesktopTurnStreamCoordinator streams;
    private final Supplier<JavaClawClient> client;
    private final AtomicLong epoch = new AtomicLong();
    private volatile boolean closed;
    private RecoveryFailure recoveryFailure;

    DesktopConversationCoordinator(
            DesktopStore store,
            Consumer<Runnable> ui,
            ExecutorService workers,
            DesktopTurnStreamCoordinator streams,
            Supplier<JavaClawClient> client) {
        this.store = store;
        this.ui = ui;
        this.workers = workers;
        this.streams = streams;
        this.client = client;
    }

    void reconnecting() {
        epoch.incrementAndGet();
        streams.reconnecting();
    }

    void selectWorkspace(Workspace workspace) {
        ui.accept(() -> {
            if (!connected()
                    || store.state()
                            .threads()
                            .selectedWorkspace()
                            .map(Workspace::id)
                            .filter(workspace.id()::equals)
                            .isPresent()) {
                return;
            }
            Scope scope = beginSelection();
            store.update(state -> DesktopStateProjection.selectWorkspace(state, workspace));
            workers.submit(() -> execute(scope, () -> {
                var threads = scope.client().threads().list(workspace.id());
                ui.accept(() -> {
                    if (current(scope)) {
                        applyCatalog(scope, workspace, threads, threads.stream().findFirst());
                    }
                });
            }));
        });
    }

    void selectThread(ConversationThread thread) {
        ui.accept(() -> {
            if (!connected() || (selected(thread.id()) && !retryable(thread.id()))) {
                return;
            }
            Scope scope = beginSelection();
            store.update(state -> DesktopStateProjection.selectThread(state, thread));
            load(scope, thread);
        });
    }

    void load(ConversationThread thread) {
        ui.accept(() -> {
            if (connected() && selected(thread.id())) {
                load(scope(), thread);
            }
        });
    }

    private void load(Scope scope, ConversationThread thread) {
        // 历史和活动状态共同发布，避免首次恢复尚未确认时发送第二个 Turn。
        store.update(state -> DesktopStateProjection.busy(state, true));
        workers.submit(() -> loadHistory(scope, thread));
    }

    private void loadHistory(Scope scope, ConversationThread thread) {
        try {
            if (!current(scope, thread.id())) {
                return;
            }
            TranscriptState transcript =
                    DesktopTranscriptHistory.latest(scope.client(), thread, () -> !current(scope, thread.id()));
            Optional<AgentTurn> active = DesktopTranscriptHistory.active(scope.client(), thread, transcript);
            ui.accept(() -> {
                if (current(scope, thread.id())) {
                    recoveryFailure = null;
                    store.update(state -> DesktopTranscriptHistory.apply(state, thread, transcript));
                    store.update(state -> DesktopStateProjection.busy(state, false));
                    active.ifPresent(turn -> observe(scope, thread, turn));
                }
            });
        } catch (Exception failure) {
            ui.accept(() -> failedRecovery(scope, failure));
        }
    }

    void send(String message, ExecutionOverrides execution) {
        ui.accept(() -> {
            var state = store.state();
            if (!connected()
                    || state.interaction().busy()
                    || state.threads().activeTurn().isPresent()
                    || recoveryFailure != null && recoveryFailure.epoch() == epoch.get()
                    || state.threads().selectedThread().isEmpty()) {
                return;
            }
            ConversationThread thread = state.threads().selectedThread().orElseThrow();
            Scope scope = beginSelection();
            store.update(value -> DesktopStateProjection.busy(value, true));
            workers.submit(() -> execute(scope, () -> {
                if (!current(scope, thread.id())) {
                    return;
                }
                var payload = new CoreRpcContracts.TurnStartPayload(thread.id(), execution, message);
                AgentTurn started = scope.client()
                        .turns()
                        .start(payload, CommandOptions.create(0))
                        .turn();
                ui.accept(() -> {
                    if (current(scope, thread.id())) {
                        observe(scope, thread, started);
                    }
                });
            }));
        });
    }

    private void observe(Scope scope, ConversationThread thread, AgentTurn turn) {
        if (!turn.threadId().equals(thread.id())) {
            throw new IllegalStateException("读取的 Turn 不属于当前会话");
        }
        store.update(state -> DesktopStateProjection.activeTurn(state, turn));
        if (DesktopStateProjection.terminal(turn.status()) || streams.restore(scope.client(), thread, turn)) {
            return;
        }
        workers.submit(() -> execute(
                scope,
                () -> DesktopLegacyTurnObserver.observe(
                        scope.client(),
                        thread,
                        turn,
                        store,
                        change -> ui.accept(() -> {
                            if (current(scope, thread.id())) {
                                store.update(change);
                            }
                        }),
                        () -> !current(scope, thread.id()))));
    }

    void cancel() {
        ui.accept(() -> {
            Optional<AgentTurn> selected = store.state().threads().activeTurn();
            if (!connected() || selected.isEmpty()) {
                return;
            }
            AgentTurn turn = selected.orElseThrow();
            Scope scope = scope();
            workers.submit(() -> execute(scope, () -> {
                AgentTurn current = scope.client().turns().read(turn.id());
                if (current(scope, turn.threadId())
                        && current.id().equals(turn.id())
                        && current.threadId().equals(turn.threadId())
                        && !DesktopStateProjection.terminal(current.status())) {
                    scope.client()
                            .turns()
                            .cancel(current.id(), "Desktop 用户请求停止", CommandOptions.create(current.revision()));
                }
            }));
        });
    }

    CompletableFuture<ConversationThread> navigate(ThreadId threadId) {
        CompletableFuture<ConversationThread> result = new CompletableFuture<>();
        ui.accept(() -> {
            if (!connected()) {
                result.completeExceptionally(new IllegalStateException("Desktop 尚未连接或已经关闭"));
                return;
            }
            if (selected(threadId) && !retryable(threadId)) {
                result.complete(store.state().threads().selectedThread().orElseThrow());
                return;
            }
            Scope scope = beginSelection();
            store.update(state -> DesktopStateProjection.busy(DesktopStateProjection.clearObservation(state), true));
            workers.submit(() -> navigate(scope, threadId, result));
        });
        return result;
    }

    private void navigate(Scope scope, ThreadId threadId, CompletableFuture<ConversationThread> result) {
        try {
            var loaded = DesktopTranscriptHistory.navigation(scope.client(), threadId, () -> !current(scope));
            var active = DesktopTranscriptHistory.active(scope.client(), loaded.thread(), loaded.transcript());
            ui.accept(() -> {
                if (!current(scope)) {
                    result.completeExceptionally(new IllegalStateException("会话导航已失效"));
                    return;
                }
                recoveryFailure = null;
                store.update(state -> DesktopStateProjection.selectWorkspace(state, loaded.workspace()));
                store.update(state -> DesktopStateProjection.threadCatalog(
                        state, loaded.workspace(), loaded.threads(), Optional.of(loaded.thread())));
                store.update(state -> DesktopTranscriptHistory.apply(state, loaded.thread(), loaded.transcript()));
                active.ifPresent(turn -> observe(scope, loaded.thread(), turn));
                result.complete(loaded.thread());
            });
        } catch (Exception failure) {
            ui.accept(() -> {
                failedRecovery(scope, failure);
                result.completeExceptionally(failure);
            });
        }
    }

    void createThread(String title) {
        ui.accept(() -> {
            Workspace workspace = store.state().threads().selectedWorkspace().orElseThrow();
            if (!connected()) {
                return;
            }
            Scope scope = scope();
            workers.submit(() -> execute(scope, () -> {
                ConversationThread created =
                        scope.client().threads().create(workspace.id(), title, CommandOptions.create(0));
                var threads = scope.client().threads().list(workspace.id());
                ui.accept(() -> {
                    if (current(scope)) {
                        applyCatalog(beginSelection(), workspace, threads, Optional.of(created));
                    }
                });
            }));
        });
    }

    void createWorkspace(String name, Path root, ExecutionOverrides execution) {
        ui.accept(() -> {
            if (!connected()) {
                return;
            }
            Scope scope = scope();
            workers.submit(() -> execute(scope, () -> {
                Workspace created = scope.client().workspaces().create(name, root, execution, CommandOptions.create(0));
                var workspaces = scope.client().workspaces().list();
                var threads = scope.client().threads().list(created.id());
                ui.accept(() -> {
                    if (current(scope)) {
                        beginSelection();
                        store.update(state -> DesktopStateProjection.catalog(state, workspaces, created, threads));
                    }
                });
            }));
        });
    }

    void earlier() {
        ui.accept(() -> history(false, false));
    }

    void following(boolean value) {
        ui.accept(() -> history(true, value));
    }

    private void history(boolean changeFollowing, boolean value) {
        if (closed) {
            return;
        }
        var selected = store.state().threads().selectedThread();
        if (!connected() || selected.isEmpty()) {
            if (changeFollowing) {
                // 阅读位置属于当前页面；断线或只有缓存正文时仍允许暂停跟随，不需要服务端授权或查询。
                store.update(state -> DesktopStateProjection.transcript(
                        state, state.transcript().following(value)));
            }
            return;
        }
        Scope scope = scope();
        Consumer<java.util.function.UnaryOperator<com.javaclaw.desktop.state.DesktopState>> update =
                change -> ui.accept(() -> {
                    if (current(scope, selected.orElseThrow().id())) {
                        store.update(change);
                    }
                });
        if (changeFollowing) {
            DesktopTranscriptHistory.follow(
                    value,
                    scope::client,
                    store,
                    update,
                    workers,
                    () -> !current(scope, selected.orElseThrow().id()));
        } else {
            DesktopTranscriptHistory.earlier(scope::client, store, update, workers);
        }
    }

    private void applyCatalog(
            Scope scope, Workspace workspace, List<ConversationThread> threads, Optional<ConversationThread> selected) {
        store.update(state -> DesktopStateProjection.threadCatalog(state, workspace, threads, selected));
        selected.ifPresent(thread -> load(scope, thread));
    }

    private Scope beginSelection() {
        epoch.incrementAndGet();
        recoveryFailure = null;
        streams.selectionChanged();
        return scope();
    }

    private Scope scope() {
        return new Scope(epoch.get(), Objects.requireNonNull(client.get(), "Desktop 尚未连接 App Server"));
    }

    private boolean current(Scope scope) {
        return !closed && epoch.get() == scope.epoch() && client.get() == scope.client();
    }

    /** 初始化目录尚未提交时不受理导航，防止半建立连接的旧目录覆盖用户的新选择。 */
    private boolean connected() {
        return !closed
                && client.get() != null
                && store.state().connection().status() == ConnectionState.Status.CONNECTED;
    }

    private boolean current(Scope scope, ThreadId threadId) {
        return current(scope) && selected(threadId);
    }

    private boolean selected(ThreadId threadId) {
        return store.state()
                .threads()
                .selectedThread()
                .map(ConversationThread::id)
                .filter(threadId::equals)
                .isPresent();
    }

    private boolean retryable(ThreadId threadId) {
        return recoveryFailure != null
                && recoveryFailure.epoch() == epoch.get()
                && recoveryFailure.threadId().filter(threadId::equals).isPresent();
    }

    /** 恢复失败表示活动状态未知；封锁发送直到明确重选成功，不能把错误视为没有活动 Turn。 */
    private void failedRecovery(Scope scope, Exception failure) {
        if (current(scope)) {
            recoveryFailure = new RecoveryFailure(
                    scope.epoch(), store.state().threads().selectedThread().map(ConversationThread::id));
            store.update(state -> DesktopStateProjection.recoveryFailure(
                    state, DesktopFailures.safeMessage(failure) + "；请重新选择当前会话以重试"));
        }
    }

    private void execute(Scope scope, CheckedAction action) {
        try {
            if (current(scope)) {
                action.run();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            ui.accept(() -> {
                if (current(scope)) {
                    store.update(state -> {
                        return recoveryFailure != null && recoveryFailure.epoch() == scope.epoch()
                                ? DesktopStateProjection.recoveryFailure(state, DesktopFailures.safeMessage(failure))
                                : DesktopStateProjection.failure(state, DesktopFailures.safeMessage(failure));
                    });
                }
            });
        }
    }

    @Override
    public void close() {
        closed = true;
        epoch.incrementAndGet();
    }

    private record Scope(long epoch, JavaClawClient client) {}

    private record RecoveryFailure(long epoch, Optional<ThreadId> threadId) {}

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
