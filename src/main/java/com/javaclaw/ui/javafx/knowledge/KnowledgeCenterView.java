package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.memory.embed.EmbeddingHealthStatus;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A modal knowledge-center window and the lifecycle of its complete FXML controller tree. */
public final class KnowledgeCenterView implements AutoCloseable {

    private final Stage stage;
    private final ViewHandle<StackPane> handle;
    private final KnowledgeCenterController controller;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Runnable onHidden = () -> { };

    KnowledgeCenterView(
            Stage stage,
            ViewHandle<StackPane> handle,
            KnowledgeCenterController controller,
            Runnable onConfigChanged,
            Runnable onOpenModelSettings) {
        this.stage = Objects.requireNonNull(stage, "stage");
        this.handle = Objects.requireNonNull(handle, "handle");
        this.controller = Objects.requireNonNull(controller, "controller");
        controller.configure(stage, stage::close, onConfigChanged, onOpenModelSettings);
        stage.setOnHidden(event -> close());
    }

    public void show() {
        if (closed.get()) throw new IllegalStateException("知识库中心窗口已关闭");
        controller.prepare();
        stage.show();
        stage.toFront();
    }

    public void setOnHidden(Runnable callback) {
        onHidden = callback == null ? () -> { } : callback;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        stage.setOnHidden(null);
        if (stage.isShowing()) stage.hide();
        handle.close();
        onHidden.run();
        onHidden = () -> { };
    }

    StackPane root() { return handle.root(); }

    KnowledgeCenterController controller() { return controller; }

    static EmbeddingHealthViewState embeddingHealthViewState(EmbeddingHealthStatus status) {
        return switch (status) {
            case HEALTHY -> new EmbeddingHealthViewState("RAG 正常", "已连接", "healthy");
            case CHECKING -> new EmbeddingHealthViewState("RAG 检查中", "检查中", "checking");
            case DEGRADED -> new EmbeddingHealthViewState("RAG 已降级", "已降级", "degraded");
            case UNAVAILABLE -> new EmbeddingHealthViewState("RAG 不可用", "不可用", "unavailable");
            case UNCONFIGURED -> new EmbeddingHealthViewState("RAG 未配置", "未配置", "unconfigured");
        };
    }

    static record EmbeddingHealthViewState(
            String badgeText, String connectionText, String style) { }
}
