package com.javaclaw.server.security.vault;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 在 Vault 状态提交后同步通知依赖方刷新。
 *
 * <p>独立锁串行化完整变更，门闩只在 Vault monitor 内关闭；运行时失效、普通通知和重建均在 monitor 外执行。任一安全监听器失败时门闩保持关闭，后续成功刷新才可重新开放。
 */
final class VaultChangeListeners {
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<RuntimeListener> runtimeListeners = new CopyOnWriteArrayList<>();
    private final VaultRuntimeGate runtimeGate = new VaultRuntimeGate();
    private final ReentrantLock runtimeSyncLock = new ReentrantLock();
    private final BooleanSupplier runtimeRebuildAllowed;

    VaultChangeListeners(BooleanSupplier runtimeRebuildAllowed) {
        this.runtimeRebuildAllowed = Objects.requireNonNull(runtimeRebuildAllowed, "runtimeRebuildAllowed");
    }

    void add(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    void addRuntime(Runnable invalidate, Runnable rebuild) {
        runtimeSyncLock.lock();
        try {
            runtimeListeners.add(new RuntimeListener(invalidate, rebuild));
        } finally {
            runtimeSyncLock.unlock();
        }
    }

    VaultRuntimeGate runtimeGate() {
        return runtimeGate;
    }

    <T> T afterChange(Object monitor, Supplier<T> mutation) {
        return serialize(() -> coordinate(monitor, mutation, ignored -> true));
    }

    <T> T afterAvailabilityRefresh(Object monitor, BooleanSupplier ready, Supplier<T> refresh) {
        return serialize(() -> refreshAvailability(monitor, ready, refresh));
    }

    <T> T afterConditionalChange(Object monitor, Supplier<T> mutation, Predicate<T> changed) {
        return serialize(() -> coordinate(monitor, mutation, Objects.requireNonNull(changed, "changed")));
    }

    private <T> T refreshAvailability(Object monitor, BooleanSupplier ready, Supplier<T> refresh) {
        Object checkedMonitor = Objects.requireNonNull(monitor, "monitor");
        BooleanSupplier readiness = Objects.requireNonNull(ready, "ready");
        VaultRuntimeGate.ChangeTicket ticket;
        boolean wasReady;
        boolean availabilityChanged;
        Outcome<T> outcome;
        boolean rebuildAfterRefresh;
        synchronized (checkedMonitor) {
            ticket = runtimeGate.beginChange();
            wasReady = readiness.getAsBoolean();
            outcome = capture(Objects.requireNonNull(refresh, "refresh"));
            availabilityChanged = wasReady != readiness.getAsBoolean();
            rebuildAfterRefresh = runtimeRebuildAllowed.getAsBoolean();
        }
        boolean synchronize = outcome.succeeded() || availabilityChanged;
        return finish(ticket, outcome, synchronize, availabilityChanged, rebuildAfterRefresh);
    }

    private <T> T coordinate(Object monitor, Supplier<T> mutation, Predicate<T> notifyRegular) {
        VaultRuntimeGate.ChangeTicket ticket;
        Outcome<T> outcome;
        boolean rebuildAfterChange;
        synchronized (Objects.requireNonNull(monitor, "monitor")) {
            ticket = runtimeGate.beginChange();
            outcome = capture(Objects.requireNonNull(mutation, "mutation"));
            rebuildAfterChange = runtimeRebuildAllowed.getAsBoolean();
        }
        boolean changed = outcome.succeeded() && notifyRegular.test(outcome.value());
        return finish(ticket, outcome, changed, changed, rebuildAfterChange);
    }

    private <T> T finish(
            VaultRuntimeGate.ChangeTicket ticket,
            Outcome<T> outcome,
            boolean synchronize,
            boolean notifyRegular,
            boolean rebuild) {
        RuntimeException synchronizationFailure = synchronize
                ? synchronizeRuntime(ticket, notifyRegular, rebuild)
                : completeWithoutSynchronization(ticket);
        if (outcome.failure() != null) {
            if (synchronizationFailure != null) {
                outcome.failure().addSuppressed(synchronizationFailure);
            }
            return throwUnchecked(outcome.failure());
        }
        if (synchronizationFailure != null) {
            throw synchronizationFailure;
        }
        return outcome.value();
    }

    private RuntimeException synchronizeRuntime(
            VaultRuntimeGate.ChangeTicket ticket, boolean notifyRegular, boolean rebuild) {
        RuntimeException failure = runRuntimePhase(true, null);
        if (notifyRegular) {
            notifyRegularListeners();
        }
        if (rebuild) {
            failure = runRuntimePhase(false, failure);
        }
        if (failure == null && rebuild) {
            runtimeGate.completeChange(ticket);
        }
        return failure;
    }

    private RuntimeException completeWithoutSynchronization(VaultRuntimeGate.ChangeTicket ticket) {
        runtimeGate.cancelChange(ticket);
        return null;
    }

    private RuntimeException runRuntimePhase(boolean invalidate, RuntimeException aggregate) {
        RuntimeException result = aggregate;
        for (RuntimeListener listener : runtimeListeners) {
            try {
                listener.run(invalidate);
            } catch (RuntimeException failure) {
                if (result == null) {
                    result = new VaultException("Vault 已变更，但 Provider 运行时安全同步失败", failure);
                } else {
                    result.addSuppressed(failure);
                }
            }
        }
        return result;
    }

    private void notifyRegularListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException failure) {
                System.getLogger(VaultChangeListeners.class.getName())
                        .log(System.Logger.Level.WARNING, "Vault 变化后的热更新失败", failure);
            }
        }
    }

    private static <T> Outcome<T> capture(Supplier<T> mutation) {
        try {
            return new Outcome<>(mutation.get(), null);
        } catch (RuntimeException | Error failure) {
            return new Outcome<>(null, failure);
        }
    }

    private <T> T serialize(Supplier<T> operation) {
        if (runtimeSyncLock.isHeldByCurrentThread()) {
            throw new VaultException("Vault 监听器不得嵌套发起 Vault 变更");
        }
        runtimeSyncLock.lock();
        try {
            return operation.get();
        } finally {
            runtimeSyncLock.unlock();
        }
    }

    private static <T> T throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Vault 变更出现未知失败类型", failure);
    }

    private record RuntimeListener(Runnable invalidate, Runnable rebuild) {
        private RuntimeListener {
            Objects.requireNonNull(invalidate, "invalidate");
            Objects.requireNonNull(rebuild, "rebuild");
        }

        private void run(boolean invalidating) {
            (invalidating ? invalidate : rebuild).run();
        }
    }

    private record Outcome<T>(T value, Throwable failure) {
        private boolean succeeded() {
            return failure == null;
        }
    }
}
