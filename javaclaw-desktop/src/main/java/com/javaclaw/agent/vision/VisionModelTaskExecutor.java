package com.javaclaw.agent.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.InputBlock;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.CancellationToken;
import com.javaclaw.framework.spi.ModelTaskGateway;
import com.javaclaw.framework.spi.ModelTaskRequest;
import com.javaclaw.framework.spi.ModelTaskResult;
import com.javaclaw.framework.spi.ModelTier;
import com.javaclaw.framework.spi.RunCancelledException;
import com.javaclaw.util.SensitiveDataRedactor;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 视觉等待有独立期限；停止本次观察不会取消整个用户任务。 */
final class VisionModelTaskExecutor {
    private final ModelTaskGateway gateway;
    private final RunId ownerRunId;
    private final CancellationToken owner;

    VisionModelTaskExecutor(ModelTaskGateway gateway, RunId ownerRunId, CancellationToken owner) {
        this.gateway = gateway;
        this.ownerRunId = ownerRunId;
        this.owner = owner;
    }

    void requireActive() {
        owner.throwIfCancelled();
        if (Thread.currentThread().isInterrupted() || owner.remaining().isZero()
                || owner.remaining().isNegative()) throw new RunCancelledException();
    }

    /** An optional candidate repair may lose OCR quality, never swallow owner control failures. */
    void propagateControlFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new java.util.ArrayDeque<Throwable>();
        if (failure != null) pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof RunCancelledException cancelled) throw cancelled;
            if (current instanceof com.javaclaw.framework.api.BudgetExceededException exceeded) throw exceeded;
            if (current instanceof com.javaclaw.framework.api.TurnPausedException paused) throw paused;
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new RunCancelledException();
            }
            if (current.getCause() != null) pending.add(current.getCause());
            Collections.addAll(pending, current.getSuppressed());
        }
        // Preserve the recorded pause/budget cause before checking a subsequently expired owner.
        requireActive();
    }

    JsonNode execute(String purpose, JsonNode input, List<InputBlock> images,
                     JsonNode schema, Duration timeout) throws Exception {
        owner.throwIfCancelled();
        Duration remaining = owner.remaining();
        Duration limit = remaining.compareTo(timeout) < 0 ? remaining : timeout;
        if (limit.isZero() || limit.isNegative()) throw new RunCancelledException();
        CallCancellation call = new CallCancellation(owner, limit);
        var future = new CompletableFuture<ModelTaskResult>();
        var submitted = new AtomicReference<CompletableFuture<ModelTaskResult>>();
        Thread carrier = Thread.ofVirtual().name("javaclaw-vision-submit").unstarted(() -> {
            try {
                call.throwIfCancelled();
                var task = gateway.execute(new ModelTaskRequest(purpose, ModelTier.NORMAL, input,
                        images, schema, ownerRunId, "vision", limit, 0, call, false)).toCompletableFuture();
                submitted.set(task);
                if (call.cancelled()) cancelAsync(task);
                task.whenComplete((result, failure) -> {
                    if (failure != null) future.completeExceptionally(failure);
                    else future.complete(result);
                });
            } catch (Throwable failure) { future.completeExceptionally(failure); }
        });
        try (var registration = owner.onCancel(call::cancel)) {
            // 入场审计和 executor.submit 也可能阻塞，须包含在同一视觉等待期限内。
            carrier.start();
            // 默认 token 可以只有 cancelled()，轮询保证这类拥有者的停止也及时生效。
            while (true) {
                owner.throwIfCancelled();
                long nanos = call.remaining().toNanos();
                if (nanos <= 0) throw new TimeoutException("视觉任务超时: " + purpose + " (" + limit + ")");
                try {
                    ModelTaskResult result = future.get(Math.min(nanos, TimeUnit.MILLISECONDS.toNanos(100)),
                            TimeUnit.NANOSECONDS);
                    owner.throwIfCancelled();
                    return result.output();
                } catch (TimeoutException tick) {
                    if (call.remaining().isZero()) {
                        throw new TimeoutException("视觉任务超时: " + purpose + " (" + limit + ")");
                    }
                }
            }
        } catch (ExecutionException failure) {
            owner.throwIfCancelled();
            if (failure.getCause() instanceof RunCancelledException cancelled) throw cancelled;
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RunCancelledException();
        } finally {
            // 即使 gateway 的公共 stage 卡在审计/物理结束，实际任务仍收到取消通知。
            call.cancel();
            carrier.interrupt();
            future.cancel(false);
            CompletableFuture<ModelTaskResult> pending = submitted.get();
            if (pending != null) cancelAsync(pending);
        }
    }

    private static void cancelAsync(CompletableFuture<ModelTaskResult> pending) {
        if (!pending.isDone()) Thread.ofVirtual().name("javaclaw-vision-cancel").start(() -> pending.cancel(true));
    }

    static String failureSummary(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        appendFailure(text, failure, "", seen);
        return text.toString();
    }

    private static void appendFailure(StringBuilder text, Throwable failure, String relation,
                                      Set<Throwable> seen) {
        if (failure == null || seen.size() >= 12 || !seen.add(failure)) return;
        if (!text.isEmpty()) text.append("; ");
        text.append(relation).append(failure.getClass().getSimpleName()).append(": ");
        String message = SensitiveDataRedactor.redactText(failure.getMessage());
        text.append(message.length() <= 1_000 ? message : message.substring(0, 1_000));
        for (Throwable suppressed : failure.getSuppressed()) appendFailure(text, suppressed, "suppressed ", seen);
        appendFailure(text, failure.getCause(), "caused by ", seen);
    }

    private static final class CallCancellation implements CancellationToken {
        private final CancellationToken owner;
        private final long started = System.nanoTime();
        private final long timeoutNanos;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

        private CallCancellation(CancellationToken owner, Duration timeout) {
            this.owner = owner;
            this.timeoutNanos = timeout.toNanos();
        }

        @Override public boolean cancelled() { return stopped.get() || owner.cancelled(); }

        @Override public Duration remaining() {
            Duration local = Duration.ofNanos(Math.max(0, timeoutNanos - (System.nanoTime() - started)));
            Duration parent = owner.remaining();
            return local.compareTo(parent) < 0 ? local : parent;
        }

        @Override public CancellationRegistration onCancel(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            synchronized (callbacks) {
                if (!cancelled()) {
                    callbacks.add(callback);
                    return () -> callbacks.remove(callback);
                }
            }
            dispatch(callback);
            return () -> { };
        }

        private void cancel() {
            if (!stopped.compareAndSet(false, true)) return;
            List<Runnable> pending;
            synchronized (callbacks) {
                pending = List.copyOf(callbacks);
                callbacks.clear();
            }
            // 一个取消消费者可能同步触发阻塞的审计；不得绑住等待者或其他消费者。
            pending.forEach(CallCancellation::dispatch);
        }

        private static void dispatch(Runnable callback) {
            Thread.ofVirtual().name("javaclaw-vision-cancel-signal").start(() -> {
                try { callback.run(); } catch (RuntimeException ignored) { /* 不覆盖原始失败。 */ }
            });
        }
    }
}
