package com.javaclaw.chat;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.HBox;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一张主动澄清卡的 FXML 生命周期门面。 */
final class ClarificationCardView implements AutoCloseable {

    private final ViewHandle<HBox> handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    ClarificationCardView(ViewHandle<HBox> handle) {
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    HBox root() { return handle.root(); }

    ClarificationCardController controller() {
        return handle.controller(ClarificationCardController.class);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) handle.close();
    }
}
