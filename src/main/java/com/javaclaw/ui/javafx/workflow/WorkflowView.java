package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.application.workflow.WorkflowApplicationService.WorkflowItem;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 一个工作流中心窗口及其完整 FXML Controller 树生命周期。 */
public final class WorkflowView implements AutoCloseable {

    private static final long CLOSE_TIMEOUT_SECONDS = 6;

    private final Stage stage;
    private final ViewHandle<StackPane> handle;
    private final WorkflowViewController controller;
    private final FxDispatcher fx;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();

    WorkflowView(
            Stage stage,
            ViewHandle<StackPane> handle,
            WorkflowViewController controller,
            FxDispatcher fx,
            Consumer<WorkflowItem> onPublished) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.fx = Objects.requireNonNull(fx, "fx");
        controller.configure(onPublished, this::requestClose);
        stage.setOnCloseRequest(event -> {
            event.consume();
            requestClose();
        });
        stage.setOnHidden(event -> {
            if (!closing.get()) close();
        });
    }

    public void show() {
        ensureOpen();
        controller.prepare();
        stage.show();
        stage.toFront();
    }

    public boolean isShowing() {
        return !closed.get() && stage.isShowing();
    }

    StackPane root() { return handle.root(); }

    WorkflowViewController controller() { return controller; }

    /** 用户关闭：仅在最新草稿保存成功后销毁窗口。 */
    private void requestClose() {
        if (closed.get() || !closing.compareAndSet(false, true)) return;
        controller.prepareClose().whenComplete((saved, failure) -> fx.dispatch(() -> {
            if (failure == null && Boolean.TRUE.equals(saved)) {
                finishClose();
            } else {
                closing.set(false);
            }
        }));
    }

    /** 工作区关闭：尽力等待最新草稿落盘，失败或超时仍必须释放旧 Context 页面。 */
    @Override
    public void close() {
        if (closed.get() || !closing.compareAndSet(false, true)) return;
        if (fx.isFxThread()) {
            controller.prepareClose().whenComplete(
                    (saved, failure) -> fx.dispatch(this::finishClose));
            return;
        }
        try {
            fx.call(controller::prepareClose)
                    .thenCompose(future -> future)
                    .get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // Context 关闭不能因草稿存储故障永久卡住；控制器已经把保存失败写入运行轨迹。
        }
        try {
            fx.call(() -> {
                finishClose();
                return null;
            }).get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closing.set(false);
            throw new IllegalStateException("关闭工作流中心被中断", interrupted);
        } catch (Exception failure) {
            closing.set(false);
            throw new IllegalStateException("关闭工作流中心失败", failure);
        }
    }

    private void finishClose() {
        if (!closed.compareAndSet(false, true)) return;
        stage.setOnCloseRequest(null);
        stage.setOnHidden(null);
        if (stage.isShowing()) stage.hide();
        handle.close();
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("工作流中心已关闭");
    }
}
