package com.javaclaw.desktop.web;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.PickResult;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSurfaceMenuTest {
    @Test
    void 三个健康页面都能通过原生菜单主动降级重试并在关闭时释放菜单() {
        for (String kind : List.of("chat", "document", "graph")) {
            exercise(kind);
        }
    }

    private void exercise(String kind) {
        AtomicInteger business = new AtomicInteger();
        WebSurfaceHost host = FxTestSupport.call(
                () -> new WebSurfaceHost(kind, new Label("原生正文 🙂"), (action, value) -> business.incrementAndGet()));
        Stage stage = FxTestSupport.call(() -> {
            Stage result = new Stage();
            Scene scene = new Scene(host, 600, 450);
            DesktopStylesheets.apply(scene);
            result.setScene(scene);
            result.show();
            host.show("menu", "{}");
            return result;
        });
        try {
            FxTestSupport.await(() -> FxTestSupport.call(host::acknowledged));
            FxTestSupport.run(() -> {
                Node web = host.getChildren().stream()
                        .filter(WebView.class::isInstance)
                        .findFirst()
                        .orElseThrow();
                ContextMenu menu = open(web, stage);
                assertEquals(
                        List.of("简版显示", "重试显示"),
                        menu.getItems().stream().map(item -> item.getText()).toList());
                menu.getItems().getFirst().fire();
                assertFalse(menu.isShowing());
                assertTrue(host.getChildren().stream().noneMatch(WebView.class::isInstance));
                assertFalse(host.acknowledged());
                ContextMenu retry = open(host, stage);
                assertTrue(retry.getItems().getFirst().isDisable());
                retry.getItems().getLast().fire();
                assertFalse(retry.isShowing());
            });
            FxTestSupport.await(() -> FxTestSupport.call(host::acknowledged));
            FxTestSupport.run(() -> {
                ContextMenu menu = open(host, stage);
                host.suspend();
                assertFalse(menu.isShowing());
                host.resume();
            });
            FxTestSupport.await(() -> FxTestSupport.call(host::acknowledged));
            FxTestSupport.run(() -> {
                ContextMenu menu = open(host, stage);
                host.close();
                assertFalse(menu.isShowing());
                assertEquals(0, business.get());
            });
        } finally {
            FxTestSupport.run(() -> {
                host.close();
                stage.close();
            });
        }
    }

    private static ContextMenu open(Node target, Stage stage) {
        target.fireEvent(new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                20,
                20,
                stage.getX() + 20,
                stage.getY() + 20,
                false,
                new PickResult(target, 20, 20)));
        return Window.getWindows().stream()
                .filter(ContextMenu.class::isInstance)
                .map(ContextMenu.class::cast)
                .filter(Window::isShowing)
                .findFirst()
                .orElseThrow();
    }
}
