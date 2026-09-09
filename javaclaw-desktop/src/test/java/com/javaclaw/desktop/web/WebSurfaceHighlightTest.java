package com.javaclaw.desktop.web;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSurfaceHighlightTest {
    @Test
    void 延迟高亮保留代码选区并在选区离开后着色且保留横滚() {
        try (Fixture fixture = open()) {
            FxTestSupport.run(() -> {
                fixture.script("""
                        document.getElementById('surface').innerHTML = '<pre><code class="language-java"></code></pre>';
                        window.code = document.querySelector('code');
                        code.textContent = 'public class Example { String text = "' + 'long text '.repeat(100) + '"; }';
                        window.pre = document.querySelector('pre');
                        pre.scrollLeft = 90;
                        window.offsetBefore = pre.scrollLeft;
                        const range = document.createRange();
                        range.setStart(code.firstChild, 0); range.setEnd(code.firstChild, 12);
                        getSelection().removeAllRanges(); getSelection().addRange(range);
                        window.selectionBefore = getSelection().toString();
                        JavaClawSurface.highlight(document.getElementById('surface'));
                        requestAnimationFrame(() => requestAnimationFrame(() => window.highlightAttempted = true));
                        """);
            });
            fixture.await("window.highlightAttempted === true");
            FxTestSupport.run(() -> {
                assertTrue(fixture.truth("offsetBefore > 0"));
                assertTrue(fixture.truth("code.children.length === 0"));
                assertEquals(fixture.script("selectionBefore"), fixture.script("getSelection().toString()"));
                fixture.script("getSelection().removeAllRanges()");
            });
            fixture.await("code.querySelector('.hljs-keyword') !== null");
            FxTestSupport.run(() -> assertEquals(fixture.script("offsetBefore"), fixture.script("pre.scrollLeft")));
        }
    }

    @Test
    void 文档离屏代码等待滚动进入视口后再高亮() {
        try (Fixture fixture = open()) {
            FxTestSupport.run(() -> fixture.script("""
                    document.getElementById('surface').innerHTML = '<div style="height:1500px"></div>'
                            + '<pre><code class="language-java">public class Example {}</code></pre>';
                    window.code = document.querySelector('code');
                    JavaClawSurface.highlight(document.getElementById('surface'));
                    requestAnimationFrame(() => requestAnimationFrame(() => window.highlightAttempted = true));
                    """));
            fixture.await("window.highlightAttempted === true");
            FxTestSupport.run(() -> {
                assertTrue(fixture.truth("code.children.length === 0"));
                fixture.script("code.scrollIntoView({block:'center'})");
            });
            fixture.await("code.querySelector('.hljs-keyword') !== null");
        }
    }

    private static Fixture open() {
        Fixture fixture = FxTestSupport.call(() -> {
            WebSurfaceHost host = new WebSurfaceHost("document", new Label("简版"), (action, value) -> {});
            Stage stage = new Stage();
            Scene scene = new Scene(host, 700, 400);
            DesktopStylesheets.apply(scene);
            stage.setScene(scene);
            stage.show();
            host.show("highlight", "{}");
            return new Fixture(host, stage);
        });
        FxTestSupport.await(() -> FxTestSupport.call(fixture.host()::acknowledged));
        return fixture;
    }

    private record Fixture(WebSurfaceHost host, Stage stage) implements AutoCloseable {
        Object script(String script) {
            return engine().executeScript(script);
        }

        boolean truth(String script) {
            return Boolean.TRUE.equals(script(script));
        }

        void await(String script) {
            FxTestSupport.await(() -> FxTestSupport.call(() -> truth(script)));
        }

        private WebEngine engine() {
            return host.getChildren().stream()
                    .filter(WebView.class::isInstance)
                    .map(WebView.class::cast)
                    .findFirst()
                    .orElseThrow()
                    .getEngine();
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
