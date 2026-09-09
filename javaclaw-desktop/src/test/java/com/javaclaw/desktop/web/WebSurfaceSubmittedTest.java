package com.javaclaw.desktop.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.protocol.CanonicalJson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSurfaceSubmittedTest {
    @Test
    void 旧完整载荷回调等待新版本提交期间拒绝旧DOM动作() {
        try (Harness fixture = new Harness()) {
            fixture.await("first");
            FxTestSupport.run(() -> fixture.host.show("thread", "{\"items\":[]}"));
            fixture.await("");
            FxTestSupport.run(() -> {
                fixture.postPreview();
                assertEquals(List.of("legacy:target"), fixture.actions);
                fixture.host.show("thread", "{\"items\":[],\"hasEarlier\":true}");
                fixture.postPreview();
                assertEquals(List.of("legacy:target"), fixture.actions);
            });
            fixture.await("");
            FxTestSupport.run(() -> {
                fixture.postPreview();
                assertEquals(List.of("legacy:target", "legacy:target"), fixture.actions);
            });
        }
    }

    @Test
    void 等待提交与等待Ack期间点击都使用可见版本的精确映射且跳过待发版本() {
        try (Harness fixture = new Harness()) {
            fixture.await("first");
            FxTestSupport.run(() -> {
                fixture.holdAcks();
                fixture.show("thread", "second");
                fixture.postPreview();
                assertEquals(List.of("first:target"), fixture.actions);
            });
            fixture.awaitVisible("second");
            long second = FxTestSupport.call(() -> fixture.submissionRevision());
            FxTestSupport.run(() -> {
                assertFalse(fixture.host.acknowledged());
                fixture.postPreview();
                fixture.show("thread", "skipped");
                fixture.show("thread", "fourth");
                fixture.postPreview();
                assertEquals(List.of("first:target", "second:target", "second:target"), fixture.actions);
                fixture.script("window.savedBridge.post(window.heldAck)");
            });
            fixture.awaitVisible("fourth");
            FxTestSupport.run(() -> {
                assertEquals("patch", fixture.lastPayload().get("mode"));
                assertEquals(second, ((Number) fixture.lastPayload().get("baseRevision")).longValue());
                fixture.postPreview();
                assertEquals("fourth:target", fixture.actions.getLast());
            });
        }
    }

    @Test
    void 切换上下文立即拒绝旧DOM动作且新的页面不复用旧基线() {
        try (Harness fixture = new Harness()) {
            fixture.await("first");
            FxTestSupport.run(() -> {
                fixture.trackSubmissions();
                fixture.show("other-thread", "other");
                fixture.postPreview();
                assertTrue(fixture.actions.isEmpty());
            });
            fixture.await("other");
            FxTestSupport.run(() -> {
                assertEquals("replace", fixture.lastPayload().get("mode"));
                fixture.postPreview();
                assertEquals(List.of("other:target"), fixture.actions);
            });
        }
    }

    @Test
    void 增量基线失配仅重发最新完整快照而不重建或重执行业务() {
        try (Harness fixture = new Harness()) {
            fixture.await("first");
            long generation = FxTestSupport.call(fixture.host::generation);
            FxTestSupport.run(() -> {
                fixture.trackSubmissions();
                fixture.script("""
                        (() => {
                            const apply = window.JavaClawSurface.apply;
                            let rejected = false;
                            window.JavaClawSurface.apply = (identity,revision,data,state) => {
                                if (data.mode === 'patch' && !rejected) { rejected = true; return false; }
                                return apply(identity,revision,data,state);
                            };
                        })()
                        """);
                fixture.show("thread", "recovered");
            });
            fixture.await("recovered");
            FxTestSupport.run(() -> {
                assertEquals(generation, fixture.host.generation());
                assertEquals("replace", fixture.lastPayload().get("mode"));
                assertTrue(fixture.actions.isEmpty());
            });
        }
    }

    @Test
    void 空闲与祖先隐藏时停止唤醒且隐藏反馈不参与布局() {
        try (Harness fixture = new Harness()) {
            fixture.await("first");
            FxTestSupport.await(() -> FxTestSupport.call(() -> !fixture.host.wakeScheduled()));
            FxTestSupport.run(() -> {
                assertFalse(fixture.fallback.isManaged());
                assertTrue(fixture.host.getChildren().stream()
                        .filter(node -> node.getStyleClass().contains("platform-feedback"))
                        .noneMatch(node -> node.isVisible() || node.isManaged()));
                fixture.root.setVisible(false);
                fixture.show("thread", "hidden-update");
                assertFalse(fixture.host.wakeScheduled());
                fixture.root.setVisible(true);
                assertTrue(fixture.host.wakeScheduled());
            });
            fixture.await("hidden-update");
            FxTestSupport.await(() -> FxTestSupport.call(() -> !fixture.host.wakeScheduled()));
        }
    }

    private static final class Harness implements AutoCloseable {
        private final CanonicalJson json = new CanonicalJson();
        private final List<String> actions = new ArrayList<>();
        private Label fallback;
        private WebSurfaceHost host;
        private StackPane root;
        private Stage stage;

        private Harness() {
            FxTestSupport.run(() -> {
                fallback = new Label("简版");
                host = new WebSurfaceHost("chat", fallback, (action, value) -> {
                    if (action.equals("preview")) {
                        actions.add("legacy:" + value);
                    }
                });
                root = new StackPane(host);
                Scene scene = new Scene(root, 800, 550);
                DesktopStylesheets.apply(scene);
                stage = new Stage();
                stage.setScene(scene);
                stage.show();
                show("thread", "first");
            });
        }

        private void show(String scope, String label) {
            String row = json.encode(Map.of(
                            "id",
                            "row",
                            "version",
                            label,
                            "title",
                            "助手",
                            "text",
                            label,
                            "style",
                            "message-assistant",
                            "references",
                            List.of(Map.of("id", "target", "label", "打开"))))
                    .json();
            host.show(
                    scope,
                    WebSurfaceContent.chat(List.of(new WebSurfaceContent.Row("row", label, row)), false),
                    (action, value) -> actions.add(label + ":" + value));
        }

        private void await(String marker) {
            FxTestSupport.await(() -> FxTestSupport.call(() -> host.acknowledged() && visible(marker)));
        }

        private void awaitVisible(String marker) {
            FxTestSupport.await(() -> FxTestSupport.call(
                    () -> visible(marker) && Boolean.TRUE.equals(script("typeof window.heldAck==='string'"))));
        }

        private boolean visible(String marker) {
            return Boolean.TRUE.equals(script("document.body.textContent.includes('" + marker + "')"));
        }

        private void postPreview() {
            script("window.JavaClawSurface.post('preview','target')");
        }

        private void trackSubmissions() {
            script("""
                    (() => {
                        const apply = window.JavaClawSurface.apply;
                        window.JavaClawSurface.apply = (identity,revision,data,state) => {
                            window.lastSubmission = {revision,data};
                            return apply(identity,revision,data,state);
                        };
                    })()
                    """);
        }

        private void holdAcks() {
            trackSubmissions();
            script("""
                    window.savedBridge = window.javaClawBridge;
                    window.javaClawBridge = {post: raw => {
                        if (JSON.parse(raw).action === 'ack') { window.heldAck = raw; }
                        else { window.savedBridge.post(raw); }
                    }};
                    """);
        }

        private long submissionRevision() {
            return ((Number) script("window.lastSubmission.revision")).longValue();
        }

        private Map<?, ?> lastPayload() {
            return json.decode(
                    new CanonicalPayload(
                            script("JSON.stringify(window.lastSubmission.data)").toString()),
                    Map.class);
        }

        private Object script(String source) {
            WebEngine engine = host.getChildren().stream()
                    .filter(WebView.class::isInstance)
                    .map(WebView.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .getEngine();
            return engine.executeScript(source);
        }

        @Override
        public void close() {
            FxTestSupport.run(() -> {
                host.close();
                stage.close();
            });
        }
    }
}
