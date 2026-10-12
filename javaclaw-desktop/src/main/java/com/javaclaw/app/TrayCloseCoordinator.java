package com.javaclaw.app;

import com.javaclaw.platform.fx.FxDispatcher;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Resolves a close-to-tray request without blocking either JavaFX or AWT. */
final class TrayCloseCoordinator {

    private final FxDispatcher fx;
    private final long timeoutMillis;
    private final AtomicBoolean pending = new AtomicBoolean(false);

    TrayCloseCoordinator(FxDispatcher fx) {
        this(fx, TimeUnit.SECONDS.toMillis(2));
    }

    TrayCloseCoordinator(FxDispatcher fx, long timeoutMillis) {
        this.fx = Objects.requireNonNull(fx, "fx");
        if (timeoutMillis < 1) throw new IllegalArgumentException("timeoutMillis 必须大于 0");
        this.timeoutMillis = timeoutMillis;
    }

    void request(
            Supplier<CompletableFuture<Boolean>> ensureTray,
            BooleanSupplier canHide,
            Runnable hide,
            Runnable exit,
            Consumer<Throwable> onFailure) {
        Objects.requireNonNull(ensureTray, "ensureTray");
        Objects.requireNonNull(canHide, "canHide");
        Objects.requireNonNull(hide, "hide");
        Objects.requireNonNull(exit, "exit");
        Objects.requireNonNull(onFailure, "onFailure");
        if (!pending.compareAndSet(false, true)) return;

        CompletableFuture<Boolean> check;
        try {
            check = Objects.requireNonNull(ensureTray.get(), "托盘检查未返回结果");
        } catch (Throwable failure) {
            complete(false, failure, canHide, hide, exit, onFailure);
            return;
        }
        // Apply timeout to a dependent stage so callers sharing the installation future are not
        // completed exceptionally when this particular window-close attempt times out.
        check.thenApply(value -> value)
                .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((installed, failure) -> complete(
                        Boolean.TRUE.equals(installed), failure,
                        canHide, hide, exit, onFailure));
    }

    boolean isPending() {
        return pending.get();
    }

    private void complete(
            boolean installed,
            Throwable failure,
            BooleanSupplier canHide,
            Runnable hide,
            Runnable exit,
            Consumer<Throwable> onFailure) {
        try {
            fx.dispatch(() -> {
                pending.set(false);
                if (failure == null && installed && canHide.getAsBoolean()) {
                    hide.run();
                    return;
                }
                Throwable reason = failure == null
                        ? new IllegalStateException("系统托盘注册不可用") : failure;
                onFailure.accept(reason);
                exit.run();
            });
        } catch (Throwable dispatchFailure) {
            pending.set(false);
            onFailure.accept(dispatchFailure);
            exit.run();
        }
    }
}
