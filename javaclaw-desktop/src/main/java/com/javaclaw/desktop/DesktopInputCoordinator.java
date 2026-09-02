package com.javaclaw.desktop;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.InputRequestRecord;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.ConnectionState;

/**
 * 通过 Java SDK 观察并决议全局 InputRequest，不接触 App Server 实现。
 *
 * <p><strong>并发不变量：</strong>连接 epoch 排除旧会话，输入 epoch 排除操作开始前的列表响应和后续操作之前的命令响应。RPC 运行在虚拟线程；所有状态与 Future 完成都切回 UI 调度器。
 */
final class DesktopInputCoordinator {
    private static final long POLL_INTERVAL_MILLIS = 350;

    private final DesktopStore store;
    private final ExecutorService workers;
    private final Consumer<Runnable> ui;
    private final BiPredicate<Long, JavaClawClient> currentSession;
    private final AtomicLong lifecycleEpoch = new AtomicLong();
    private final AtomicLong requestEpoch = new AtomicLong();

    DesktopInputCoordinator(
            DesktopStore store,
            ExecutorService workers,
            Consumer<Runnable> ui,
            BiPredicate<Long, JavaClawClient> currentSession) {
        this.store = Objects.requireNonNull(store, "store");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.currentSession = Objects.requireNonNull(currentSession, "currentSession");
    }

    void connected(long connectionEpoch, JavaClawClient client) {
        JavaClawClient checked = Objects.requireNonNull(client, "client");
        long lifecycle = lifecycleEpoch.incrementAndGet();
        long epoch = requestEpoch.incrementAndGet();
        publish(state -> DesktopStateProjection.inputs(state, List.of(), epoch));
        workers.submit(() -> observe(connectionEpoch, lifecycle, checked));
    }

    void invalidate() {
        lifecycleEpoch.incrementAndGet();
        long epoch = requestEpoch.incrementAndGet();
        publish(state -> DesktopStateProjection.inputs(state, List.of(), epoch));
    }

    CompletableFuture<InputRequestRecord> resolve(
            long connectionEpoch, JavaClawClient client, InputRequestRecord request, CanonicalPayload response) {
        Objects.requireNonNull(response, "response");
        return submit(
                connectionEpoch,
                client,
                request,
                connected -> connected
                        .inputs()
                        .resolve(request.request().id(), response, CommandOptions.create(request.revision())));
    }

    CompletableFuture<AgentTurn> cancel(long connectionEpoch, JavaClawClient client, InputRequestRecord request) {
        return submit(connectionEpoch, client, request, connected -> {
            AgentTurn turn = connected.turns().read(request.request().turnId());
            return connected.turns().cancel(turn.id(), "Desktop 用户取消等待输入及所属任务", CommandOptions.create(turn.revision()));
        });
    }

    private <T> CompletableFuture<T> submit(
            long connectionEpoch,
            JavaClawClient client,
            InputRequestRecord request,
            java.util.function.Function<JavaClawClient, T> command) {
        JavaClawClient checkedClient = Objects.requireNonNull(client, "client");
        InputRequestRecord checkedRequest = requireCurrent(Objects.requireNonNull(request, "request"));
        long epoch = requestEpoch.incrementAndGet();
        publish(state -> DesktopStateProjection.inputOperation(state, checkedRequest, epoch));
        CompletableFuture<T> result = new CompletableFuture<>();
        workers.submit(() -> execute(connectionEpoch, checkedClient, checkedRequest, epoch, command, result));
        return result;
    }

    private <T> void execute(
            long connectionEpoch,
            JavaClawClient client,
            InputRequestRecord request,
            long epoch,
            java.util.function.Function<JavaClawClient, T> command,
            CompletableFuture<T> result) {
        try {
            T value = command.apply(client);
            List<InputRequestRecord> pending = client.inputs().list(Optional.empty(), false);
            complete(connectionEpoch, client, epoch, pending, value, result);
        } catch (Exception failure) {
            fail(connectionEpoch, client, epoch, failure, result);
        }
    }

    private <T> void complete(
            long connectionEpoch,
            JavaClawClient client,
            long epoch,
            List<InputRequestRecord> pending,
            T value,
            CompletableFuture<T> result) {
        ui.accept(() -> {
            if (current(connectionEpoch, client, epoch)) {
                store.update(state -> DesktopStateProjection.inputs(state, pending, epoch));
            }
            result.complete(value);
        });
    }

    private <T> void fail(
            long connectionEpoch, JavaClawClient client, long epoch, Exception failure, CompletableFuture<T> result) {
        String message = DesktopFailures.safeMessage(failure);
        ui.accept(() -> {
            if (current(connectionEpoch, client, epoch)) {
                store.update(state -> DesktopStateProjection.inputOperationFailure(state, message, epoch));
                disconnectIfTransportFailed(failure);
            }
            result.completeExceptionally(failure);
        });
    }

    private void observe(long connectionEpoch, long lifecycle, JavaClawClient client) {
        while (currentLifecycle(connectionEpoch, lifecycle, client)) {
            long epoch = requestEpoch.get();
            try {
                List<InputRequestRecord> requests = client.inputs().list(Optional.empty(), false);
                publishIfCurrent(connectionEpoch, lifecycle, client, epoch, requests);
            } catch (Exception failure) {
                publishObservationFailure(connectionEpoch, lifecycle, client, epoch, failure);
            }
            if (!pause()) {
                return;
            }
        }
    }

    private void publishIfCurrent(
            long connectionEpoch,
            long lifecycle,
            JavaClawClient client,
            long epoch,
            List<InputRequestRecord> requests) {
        ui.accept(() -> {
            if (currentLifecycle(connectionEpoch, lifecycle, client) && requestEpoch.get() == epoch) {
                store.update(state -> DesktopStateProjection.inputs(state, requests, epoch));
            }
        });
    }

    private void publishObservationFailure(
            long connectionEpoch, long lifecycle, JavaClawClient client, long epoch, Exception failure) {
        ui.accept(() -> {
            if (!currentLifecycle(connectionEpoch, lifecycle, client) || requestEpoch.get() != epoch) {
                return;
            }
            store.update(
                    state -> DesktopStateProjection.inputFailure(state, DesktopFailures.safeMessage(failure), epoch));
            disconnectIfTransportFailed(failure);
        });
    }

    private InputRequestRecord requireCurrent(InputRequestRecord request) {
        return store.state().interaction().inputs().pendingRequests().stream()
                .filter(candidate ->
                        candidate.request().id().equals(request.request().id()))
                .filter(candidate -> candidate.revision() == request.revision())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("输入请求已经变化，请等待刷新后重试"));
    }

    private boolean current(long connectionEpoch, JavaClawClient client, long epoch) {
        return currentSession.test(connectionEpoch, client) && requestEpoch.get() == epoch;
    }

    private boolean currentLifecycle(long connectionEpoch, long lifecycle, JavaClawClient client) {
        return lifecycleEpoch.get() == lifecycle && currentSession.test(connectionEpoch, client);
    }

    private void publish(java.util.function.UnaryOperator<com.javaclaw.desktop.state.DesktopState> change) {
        ui.accept(() -> store.update(change));
    }

    private void disconnectIfTransportFailed(Exception failure) {
        if (failure instanceof UncheckedIOException) {
            lifecycleEpoch.incrementAndGet();
            String message = DesktopFailures.safeMessage(failure);
            store.update(state -> DesktopStateProjection.connection(state, ConnectionState.failed(message)));
        }
    }

    private static boolean pause() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
            return true;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
