package com.javaclaw.desktop.settings;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination.ModifierValue;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementCenterWindowTest {
    @Test
    void 导航说明按视口换行且滚动时不出现水平滚动条() {
        FxTestSupport.run(() -> {
            Stage main = new Stage();
            main.setScene(new Scene(new VBox(), 600, 400));
            main.show();
            ManagementCenterWindow center =
                    new ManagementCenterWindow(new DesktopAppearanceManager(new MemoryStore()), disconnectedGateways());
            try {
                center.show(main);
                Stage management = managementStage();
                var root = management.getScene().getRoot();
                ListView<?> navigation = (ListView<?>) root.lookup(".management-navigation-list");
                int bundles = java.util.stream.IntStream.range(
                                0, navigation.getItems().size())
                        .filter(index ->
                                navigation.getItems().get(index).toString().contains("key=bundles"))
                        .findFirst()
                        .orElseThrow();

                navigation.scrollTo(bundles);
                root.applyCss();
                root.layout();

                ListCell<?> cell = navigation.lookupAll(".list-cell").stream()
                        .filter(ListCell.class::isInstance)
                        .map(ListCell.class::cast)
                        .filter(candidate -> candidate.getIndex() == bundles)
                        .findFirst()
                        .orElseThrow();
                Label detail = (Label) cell.lookup(".platform-detail-text");
                ScrollBar horizontal = navigation.lookupAll(".scroll-bar").stream()
                        .filter(ScrollBar.class::isInstance)
                        .map(ScrollBar.class::cast)
                        .filter(bar -> bar.getOrientation() == Orientation.HORIZONTAL)
                        .findFirst()
                        .orElseThrow();

                assertTrue(detail.isWrapText());
                assertTrue(detail.getWidth() < detail.prefWidth(-1));
                assertTrue(detail.getHeight() > detail.getFont().getSize() * 1.5);
                assertFalse(horizontal.isVisible());

                navigation.scrollTo(navigation.getItems().size() - 1);
                root.layout();
                assertFalse(horizontal.isVisible());
            } finally {
                center.dispose();
                main.hide();
            }
        });
    }

    @Test
    void 作用域不可用只锁定Workspace页面而保留本地全局页面() {
        assertTrue(ManagementCenterWindow.workspaceScopeRequired("plan"));
        assertTrue(ManagementCenterWindow.workspaceScopeRequired("network-grants"));
        assertTrue(ManagementCenterWindow.workspaceScopeRequired("roles"));
        assertTrue(ManagementCenterWindow.workspaceScopeRequired("permissions"));
        assertFalse(ManagementCenterWindow.workspaceScopeRequired("appearance"));
        assertFalse(ManagementCenterWindow.workspaceScopeRequired("connection"));
        assertFalse(ManagementCenterWindow.workspaceScopeRequired("providers"));
    }

    @Test
    void 未连接AppServer时快捷键和齿轮动作仍复用同一个非阻塞窗口() {
        AtomicReference<Stage> owner = new AtomicReference<>();
        AtomicReference<ManagementCenterWindow> center = new AtomicReference<>();
        FxTestSupport.run(() -> {
            Stage main = new Stage();
            Scene scene = new Scene(new VBox(), 600, 400);
            DesktopStylesheets.apply(scene);
            main.setScene(scene);
            main.show();
            DesktopAppearanceManager appearance = new DesktopAppearanceManager(new MemoryStore());
            appearance.register(scene);
            ManagementCenterWindow window = new ManagementCenterWindow(appearance, disconnectedGateways());
            window.installShortcut(scene);
            owner.set(main);
            center.set(window);

            Runnable shortcut = scene.getAccelerators().entrySet().stream()
                    .filter(entry -> entry.getKey() instanceof KeyCodeCombination key && key.getCode() == KeyCode.COMMA)
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow();
            shortcut.run();
            assertTrue(window.isShowing());
            exerciseSearchAndNavigation();
            long firstCount = managementWindowCount();
            window.show(main);
            assertEquals(firstCount, managementWindowCount());
            assertTrue(scene.getAccelerators().keySet().stream()
                    .filter(KeyCodeCombination.class::isInstance)
                    .map(KeyCodeCombination.class::cast)
                    .anyMatch(key -> key.getCode() == KeyCode.COMMA && key.getShortcut() == ModifierValue.DOWN));
        });

        FxTestSupport.run(() -> {
            center.get().close();
            assertFalse(center.get().isShowing());
            center.get().show(owner.get());
            assertTrue(center.get().isShowing());
            center.get().close();
            center.get().dispose();
            owner.get().hide();
        });
    }

    @Test
    void 未创建窗口时关闭安全且无owner不能首次显示() {
        ManagementCenterWindow center =
                new ManagementCenterWindow(new DesktopAppearanceManager(new MemoryStore()), disconnectedGateways());
        center.close();
        assertFalse(center.isShowing());
        FxTestSupport.run(() -> {
            assertThrows(NullPointerException.class, () -> center.installShortcut(null));
            assertThrows(NullPointerException.class, () -> center.show(null));
        });
    }

    @Test
    void 最后页面与可见窗口边界在隐藏后持久恢复() {
        MemoryWindowStore store = new MemoryWindowStore();
        AtomicReference<Stage> owner = new AtomicReference<>();
        AtomicReference<ManagementCenterWindow> first = new AtomicReference<>();
        FxTestSupport.run(() -> {
            Stage main = new Stage();
            main.setScene(new Scene(new VBox(), 600, 400));
            main.show();
            ManagementCenterWindow center = new ManagementCenterWindow(
                    new DesktopAppearanceManager(new MemoryStore()), disconnectedGateways(), store);
            center.show(main, "diagnostics");
            Stage management = managementStage();
            management.setX(80);
            management.setY(90);
            management.setWidth(1_100);
            management.setHeight(740);
            owner.set(main);
            first.set(center);
            center.close();

            assertEquals("diagnostics", store.value.lastPageKey());
            assertEquals(1_100, store.value.bounds().orElseThrow().width());
            assertEquals(740, store.value.bounds().orElseThrow().height());
        });

        FxTestSupport.run(() -> {
            ManagementCenterWindow restored = new ManagementCenterWindow(
                    new DesktopAppearanceManager(new MemoryStore()), disconnectedGateways(), store);
            restored.show(owner.get());
            Stage management = managementStage();
            ListView<?> navigation = (ListView<?>) management.getScene().lookup(".management-navigation-list");

            assertTrue(
                    navigation.getSelectionModel().getSelectedItem().toString().contains("title=诊断"));
            assertEquals(1_100, management.getWidth());
            assertEquals(740, management.getHeight());
            restored.close();
            first.get().close();
            restored.dispose();
            first.get().dispose();
            owner.get().hide();
        });
    }

    private static ManagementSettingsGateways disconnectedGateways() {
        DesktopPresenter presenter = new DesktopPresenter(
                notifications -> {
                    throw new java.io.IOException("未连接");
                },
                Platform::runLater,
                Clock.systemUTC());
        return SdkManagementSettingsGateways.create(presenter);
    }

    private static void exerciseSearchAndNavigation() {
        Stage management = Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(stage -> "JavaClaw 设置与管理中心".equals(stage.getTitle()))
                .findFirst()
                .orElseThrow();
        management.getScene().getRoot().applyCss();
        TextField search = (TextField) management.getScene().lookup(".settings-search-field");
        ListView<?> navigation = (ListView<?>) management.getScene().lookup(".management-navigation-list");
        StackPane actionSlot = (StackPane) management.getScene().lookup(".management-action-slot");
        assertEquals(30, navigation.getItems().size());
        assertEquals(Priority.ALWAYS, VBox.getVgrow(navigation));
        assertTrue(actionSlot.isManaged());
        search.setText("诊断");
        assertEquals(1, navigation.getItems().size());
        search.setText("不存在的页面");
        assertTrue(navigation.getItems().isEmpty());
        search.clear();
        int connection = java.util.stream.IntStream.range(
                        0, navigation.getItems().size())
                .filter(index -> navigation.getItems().get(index).toString().contains("key=connection"))
                .findFirst()
                .orElseThrow();
        navigation.getSelectionModel().select(connection);
        assertFalse(actionSlot.isManaged());
        navigation.getSelectionModel().select(1);
        assertTrue(navigation.getSelectionModel().getSelectedIndex() >= 0);
        assertTrue(actionSlot.isManaged());
    }

    private static long managementWindowCount() {
        return Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(stage -> "JavaClaw 设置与管理中心".equals(stage.getTitle()))
                .count();
    }

    private static Stage managementStage() {
        return Window.getWindows().stream()
                .filter(Stage.class::isInstance)
                .map(Stage.class::cast)
                .filter(stage -> "JavaClaw 设置与管理中心".equals(stage.getTitle()))
                .filter(Stage::isShowing)
                .findFirst()
                .orElseThrow();
    }

    private static final class MemoryStore implements AppearancePreferenceStore {
        private AppearancePreferences value = AppearancePreferences.defaults();

        @Override
        public AppearancePreferences load() {
            return value;
        }

        @Override
        public void save(AppearancePreferences preferences) {
            value = preferences;
        }
    }

    private static final class MemoryWindowStore implements ManagementWindowPreferenceStore {
        private ManagementWindowPreferences value = new ManagementWindowPreferences("diagnostics", Optional.empty());

        @Override
        public ManagementWindowPreferences load() {
            return value;
        }

        @Override
        public void save(ManagementWindowPreferences preferences) {
            value = preferences;
        }
    }
}
