package com.javaclaw.platform.fx;

import javafx.application.Platform;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * JavaFX Application Thread 的唯一通用调度入口。
 *
 * <p>已在 FX 线程时同步执行，避免无意义排队；其他线程使用 JavaFX 队列。调用方应在
 * 页面销毁时自行丢弃迟到结果，耗时工作不得放入传入的回调。</p>
 */
public final class FxDispatcher {

    private final BooleanSupplier isFxThread;
    private final Consumer<Runnable> enqueue;

    public FxDispatcher() {
        this(Platform::isFxApplicationThread, Platform::runLater);
    }

    /** 可测试构造器；生产配置使用无参构造器。 */
    public FxDispatcher(BooleanSupplier isFxThread, Consumer<Runnable> enqueue) {
        this.isFxThread = Objects.requireNonNull(isFxThread, "isFxThread");
        this.enqueue = Objects.requireNonNull(enqueue, "enqueue");
    }

    public void dispatch(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (isFxThread.getAsBoolean()) {
            action.run();
        } else {
            enqueue.accept(action);
        }
    }

    /**
     * 无论调用线程为何，都排到下一次 JavaFX pulse 前执行。用于必须等待 CSS/layout
     * 完成的视图切换；与 {@link #dispatch(Runnable)} 一样，动作不得包含阻塞工作。
     */
    public void dispatchLater(Runnable action) {
        enqueue.accept(Objects.requireNonNull(action, "action"));
    }

    public <T> CompletableFuture<T> call(Callable<T> action) {
        Objects.requireNonNull(action, "action");
        CompletableFuture<T> result = new CompletableFuture<>();
        dispatch(() -> {
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }
}
