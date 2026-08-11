package com.javaclaw.chat;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一次循环确认卡 FXML 加载及其幂等销毁句柄。 */
final class LoopDecisionView implements AutoCloseable {

    private final ViewHandle<HBox> handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    LoopDecisionView(ViewHandle<HBox> handle) {
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    HBox root() { return handle.root(); }

    LoopDecisionController controller() {
        return handle.controller(LoopDecisionController.class);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) handle.close();
    }
}
