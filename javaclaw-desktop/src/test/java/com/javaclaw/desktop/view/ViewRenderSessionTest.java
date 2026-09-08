package com.javaclaw.desktop.view;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.web.WebSurfaceHost;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewRenderSessionTest {
    @Test
    void 图谱真实绘制并在同Schema刷新时保持场景挂载和实例() {
        AtomicReference<ViewRenderSession> owner = new AtomicReference<>();
        AtomicReference<Stage> window = new AtomicReference<>();
        AtomicReference<WebSurfaceHost> surface = new AtomicReference<>();
        AtomicInteger detached = new AtomicInteger();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        AtomicReference<Thread.UncaughtExceptionHandler> previousHandler = new AtomicReference<>();
        try {
            FxTestSupport.run(() -> {
                previousHandler.set(Thread.currentThread().getUncaughtExceptionHandler());
                Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> uncaught.set(failure));
                var session = new ViewRenderSession(schema(), new ViewSchemaRenderer());
                session.apply(data("初始节点"), interactions());
                var root = (VBox) session.node();
                var graph = (VBox) root.getChildren().getLast();
                var host = (WebSurfaceHost) graph.getChildren().getLast();
                Scene scene = new Scene(root, 700, 600);
                DesktopStylesheets.apply(scene);
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                host.sceneProperty().addListener((observable, before, after) -> detached.incrementAndGet());
                owner.set(session);
                window.set(stage);
                surface.set(host);
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> surface.get().acknowledged()));
            FxTestSupport.run(() -> {
                assertDrawn(surface.get());
                owner.get().apply(data("更新节点"), interactions());
                assertSame(
                        surface.get(),
                        ((VBox) ((VBox) owner.get().node()).getChildren().getLast())
                                .getChildren()
                                .getLast());
                assertEquals(0, detached.get());
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> surface.get().acknowledged()));
            FxTestSupport.run(() -> {
                owner.get().suspend();
                assertTrue(surface.get().getChildren().stream().noneMatch(WebView.class::isInstance));
                owner.get().resume();
            });
            FxTestSupport.await(() -> FxTestSupport.call(() -> surface.get().acknowledged()));
            assertEquals(null, uncaught.get(), "WebKit canvas 不得隐式吞掉 JavaFX 绘制异常");
        } finally {
            FxTestSupport.run(() -> {
                Thread.currentThread().setUncaughtExceptionHandler(previousHandler.get());
                if (owner.get() != null) {
                    owner.get().close();
                    assertTrue(((VBox) owner.get().node()).getChildren().isEmpty());
                }
                if (window.get() != null) {
                    window.get().close();
                }
            });
        }
    }

    private static void assertDrawn(WebSurfaceHost surface) {
        WebView web = (WebView) surface.getChildren().getFirst();
        Number canvases = (Number) web.getEngine().executeScript("document.querySelectorAll('canvas').length");
        assertTrue(canvases.intValue() > 0);
        var pixels = web.snapshot(null, null);
        assertTrue(pixels.getWidth() > 300 && pixels.getHeight() > 200);
    }

    private static ViewSchema schema() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "graph",
                "知识记忆",
                List.of(
                        new ViewDataSource("nodes", "view.nodes", Map.of(), List.of(), 200),
                        new ViewDataSource("edges", "view.edges", Map.of(), List.of(), 200)),
                List.of(new ViewSchema.Graph(
                        "graph", "关系图谱", "nodes", "edges", "id", "label", "kind", "source", "target")));
    }

    private static ViewData data(String label) {
        var nodes = new ViewData.Source(
                List.of(
                        Map.of("id", "a", "label", label, "kind", "ACTIVE"),
                        Map.of("id", "b", "label", "替代记忆", "kind", "ACTIVE")),
                Map.of(),
                "",
                "",
                false,
                1,
                0,
                Optional.of("a"));
        var edges = new ViewData.Source(
                List.of(Map.of("source", "a", "target", "b")), Map.of(), "", "", false, 1, 0, Optional.empty());
        return new ViewData(Map.of("nodes", nodes, "edges", edges));
    }

    private static ViewInteractionHandler interactions() {
        return new ViewInteractionHandler() {
            @Override
            public void dirty(String formId, boolean dirty) {}

            @Override
            public void execute(ViewCommandInvocation invocation) {}

            @Override
            public void reload() {}

            @Override
            public void page(String sourceId, ViewPageDirection direction) {}

            @Override
            public void select(String sourceId, Optional<String> selectedKey) {}

            @Override
            public CompletionStage<AttachmentRef> upload(ViewAttachmentUploadRequest request) {
                return CompletableFuture.failedFuture(new IllegalStateException("不上传"));
            }
        };
    }
}
