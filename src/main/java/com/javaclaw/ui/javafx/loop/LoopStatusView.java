package com.javaclaw.ui.javafx.loop;

import com.javaclaw.loop.model.LoopStatus;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 循环状态消息的 FXML 生命周期门面。
 *
 * <p>实例及其方法受 JavaFX Application Thread 约束。关闭操作幂等，并销毁本次
 * FXML 加载创建的所有 prototype Controller。</p>
 */
public final class LoopStatusView implements AutoCloseable {

    private final ViewHandle<HBox> handle;
    private final LoopStatusController controller;
    private final AtomicBoolean closed = new AtomicBoolean();

    LoopStatusView(ViewHandle<HBox> handle, LoopStatusController controller) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    public HBox root() {
        return handle.root();
    }

    /** 原地刷新当前循环快照；空快照被忽略。 */
    public void update(LoopStatus status) {
        if (!closed.get()) controller.update(status);
    }

    /** 将仍在运行或等待的状态同步定格为用户取消。 */
    public void markCancelled() {
        if (!closed.get()) controller.markCancelled();
    }

    LoopStatusController controller() {
        return controller;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) handle.close();
    }
}
