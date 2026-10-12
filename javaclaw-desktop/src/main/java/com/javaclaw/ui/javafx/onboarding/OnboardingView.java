package com.javaclaw.ui.javafx.onboarding;

import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 首次向导窗口及其 FXML Controller 生命周期。 */
public final class OnboardingView implements AutoCloseable {

    private final Stage stage;
    private final ViewHandle<BorderPane> handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    OnboardingView(Stage stage, ViewHandle<BorderPane> handle) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.handle = Objects.requireNonNull(handle, "handle");
        stage.setOnHidden(event -> close());
    }

    public void showAndWait() {
        if (closed.get()) throw new IllegalStateException("首次向导已关闭");
        stage.showAndWait();
    }

    public Stage stage() { return stage; }

    OnboardingController controller() {
        return handle.controller(OnboardingController.class);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stage.setOnHidden(null);
        if (stage.isShowing()) stage.hide();
        handle.close();
    }
}
