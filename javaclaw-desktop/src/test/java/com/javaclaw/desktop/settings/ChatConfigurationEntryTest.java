package com.javaclaw.desktop.settings;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformStylesheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatConfigurationEntryTest {
    @Test
    void 聊天添加模型与管理模型使用各自入口且不提交配置或切换聊天模型() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture(true)) {
                fixture.choose("＋ 添加模型");

                assertEquals(1, fixture.added.get());
                assertEquals(0, fixture.managed.get());
                fixture.choose("管理模型与连接");
                assertEquals(1, fixture.added.get());
                assertEquals(1, fixture.managed.get());
                assertTrue(fixture.gateway.savedOptions.isEmpty());
                assertNull(fixture.gateway.lastProviderCreateOptions);
            }
        });
    }

    @Test
    void 既有四参构造的添加入口兼容导航到模型管理() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture(false)) {
                fixture.choose("＋ 添加模型");

                assertEquals(1, fixture.managed.get());
                assertEquals(0, fixture.added.get());
                assertTrue(fixture.gateway.savedOptions.isEmpty());
                assertNull(fixture.gateway.lastProviderCreateOptions);
            }
        });
    }

    private static final class Fixture implements AutoCloseable {
        private final ChatConfigurationPanelTest.PanelGateway gateway = new ChatConfigurationPanelTest.PanelGateway();
        private final AtomicInteger managed = new AtomicInteger();
        private final AtomicInteger added = new AtomicInteger();
        private final Stage window = new Stage();
        private final ChatConfigurationPanel panel;

        private Fixture(boolean explicitAdd) {
            panel = explicitAdd
                    ? new ChatConfigurationPanel(
                            gateway, managed::incrementAndGet, added::incrementAndGet, () -> {}, () -> {})
                    : new ChatConfigurationPanel(gateway, managed::incrementAndGet, () -> {}, () -> {});
            panel.bind(Optional.of(DesktopTestFixtures.workspace()), Optional.of(DesktopTestFixtures.thread()));
            Scene scene = new Scene(panel, 700, 300);
            PlatformStylesheets.apply(scene);
            window.setScene(scene);
            window.show();
            panel.applyCss();
            panel.layout();
        }

        private void choose(String text) {
            Button picker = (Button) panel.lookup("#chatModel");
            picker.fire();
            ContextMenu menu = Window.getWindows().stream()
                    .filter(ContextMenu.class::isInstance)
                    .map(ContextMenu.class::cast)
                    .filter(candidate -> candidate.getOwnerNode() == picker && candidate.isShowing())
                    .findFirst()
                    .orElseThrow();
            menu.getScene().getRoot().applyCss();
            menu.getScene().getRoot().lookupAll(".button").stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> text.equals(button.getText()))
                    .findFirst()
                    .orElseThrow()
                    .fire();
        }

        @Override
        public void close() {
            panel.close();
            window.hide();
        }
    }
}
