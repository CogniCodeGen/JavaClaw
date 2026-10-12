package com.javaclaw.ui.javafx.skill;

import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一个技能中心窗口及其完整 FXML Controller 树生命周期。 */
public final class SkillCenterView implements AutoCloseable {

    private static final long CLOSE_TIMEOUT_SECONDS = 6;

    private final Stage stage;
    private final ViewHandle<StackPane> handle;
    private final SkillCenterController controller;
    private final FxDispatcher fx;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean prepared = new AtomicBoolean();

    SkillCenterView(
            Stage stage,
            ViewHandle<StackPane> handle,
            SkillCenterController controller,
            FxDispatcher fx) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.fx = Objects.requireNonNull(fx, "fx");
        controller.configure(stage::close);
        stage.setOnHidden(event -> finishClose());
    }

    /** 以窗口模态、非阻塞方式显示；供测试和截图驱动使用。 */
    public void show() {
        prepare();
        stage.show();
        stage.toFront();
    }

    /** 以窗口模态方式显示并等待关闭，保持原技能中心调用语义。 */
    public void showAndWait() {
        prepare();
        stage.showAndWait();
    }

    public boolean isShowing() {
        return !closed.get() && stage.isShowing();
    }

    StackPane root() { return handle.root(); }
    SkillCenterController controller() { return controller; }

    private void prepare() {
        if (closed.get()) throw new IllegalStateException("技能中心窗口已关闭");
        if (prepared.compareAndSet(false, true)) controller.prepare();
    }

    @Override
    public void close() {
        if (closed.get()) return;
        if (fx.isFxThread()) {
            finishClose();
            return;
        }
        try {
            fx.call(() -> {
                finishClose();
                return null;
            }).get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("关闭技能中心被中断", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("关闭技能中心失败", failure);
        }
    }

    private void finishClose() {
        if (!closed.compareAndSet(false, true)) return;
        stage.setOnHidden(null);
        if (stage.isShowing()) stage.hide();
        handle.close();
    }
}
