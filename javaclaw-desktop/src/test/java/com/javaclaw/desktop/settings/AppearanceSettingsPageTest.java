package com.javaclaw.desktop.settings;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.appearance.InterfaceDensity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppearanceSettingsPageTest {
    @Test
    void 控件即时预览保存并在取消时恢复() {
        MemoryStore store = new MemoryStore();
        DesktopAppearanceManager manager = new DesktopAppearanceManager(store);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<VBox> applicationRoot = new AtomicReference<>();

        FxTestSupport.run(() -> {
            applicationRoot.set(new VBox());
            manager.register(new Scene(applicationRoot.get()));
            AppearanceSettingsPage page = new AppearanceSettingsPage(manager, () -> closed.set(true));
            attach(page);

            toggle(page, AppearanceTheme.CARBON).setSelected(true);
            toggle(page, FontScale.LARGE).setSelected(true);
            toggle(page, InterfaceDensity.COMPACT).setSelected(true);
            assertEquals(
                    new AppearancePreferences(AppearanceTheme.CARBON, FontScale.LARGE, InterfaceDensity.COMPACT),
                    manager.applied());
            assertTrue(applicationRoot.get().getStyleClass().contains("theme-carbon"));

            button(page, "保存外观").fire();
            assertEquals(manager.applied(), store.value);

            toggle(page, AppearanceTheme.PLUM).setSelected(true);
            button(page, "取消").fire();
            assertTrue(closed.get());
            assertEquals(store.value, manager.applied());
            assertTrue(applicationRoot.get().getStyleClass().contains("theme-carbon"));
        });
    }

    @Test
    void 保存失败恢复已保存外观且保留草稿供修正() {
        DesktopAppearanceManager manager = new DesktopAppearanceManager(new FailingStore());
        FxTestSupport.run(() -> {
            manager.register(new Scene(new VBox()));
            AppearanceSettingsPage page = new AppearanceSettingsPage(manager, () -> {});
            attach(page);
            ToggleButton plum = toggle(page, AppearanceTheme.PLUM);
            plum.setSelected(true);
            plum.setSelected(false);
            assertTrue(plum.isSelected(), "主题选项不能被清空");

            button(page, "保存外观").fire();

            assertEquals(AppearancePreferences.defaults(), manager.applied());
            assertEquals(AppearanceTheme.PLUM, page.draft().theme());
            assertTrue(descendants(page).stream()
                    .filter(Label.class::isInstance)
                    .map(Label.class::cast)
                    .anyMatch(label -> label.getText().contains("只读")));
            page.cancelDraft();
            assertEquals(AppearancePreferences.defaults(), page.draft());
            assertThrows(NullPointerException.class, () -> new AppearanceSettingsPage(null, () -> {}));
            assertThrows(NullPointerException.class, () -> new AppearanceSettingsPage(manager, null));
        });
    }

    private static ToggleButton toggle(Parent root, Object value) {
        return descendants(root).stream()
                .filter(ToggleButton.class::isInstance)
                .map(ToggleButton.class::cast)
                .filter(button -> value.equals(button.getUserData()))
                .findFirst()
                .orElseThrow();
    }

    private static Button button(Parent root, String text) {
        Parent searchRoot = root.getScene() == null ? root : root.getScene().getRoot();
        return descendants(searchRoot).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static void attach(AppearanceSettingsPage page) {
        VBox root = new VBox(page);
        page.actionContent().ifPresent(root.getChildren()::add);
        new Scene(root, 900, 700);
        root.applyCss();
    }

    private static java.util.List<Node> descendants(Parent root) {
        Parent searchRoot = root.getScene() == null ? root : root.getScene().getRoot();
        java.util.ArrayList<Node> result = new java.util.ArrayList<>();
        Queue<Node> pending = new ArrayDeque<>();
        pending.add(searchRoot);
        while (!pending.isEmpty()) {
            Node node = pending.remove();
            result.add(node);
            if (node instanceof Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return result;
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

    private static final class FailingStore implements AppearancePreferenceStore {
        @Override
        public AppearancePreferences load() {
            return AppearancePreferences.defaults();
        }

        @Override
        public void save(AppearancePreferences preferences) {
            throw new IllegalStateException("本地偏好只读");
        }
    }
}
