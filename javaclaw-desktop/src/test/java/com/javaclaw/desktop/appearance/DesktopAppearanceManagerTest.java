package com.javaclaw.desktop.appearance;

import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopAppearanceManagerTest {
    @Test
    void 预览保存取消会同步应用到全部Scene() {
        MemoryStore store = new MemoryStore(AppearancePreferences.defaults());
        DesktopAppearanceManager manager = new DesktopAppearanceManager(store);
        AtomicReference<VBox> firstRoot = new AtomicReference<>();
        AtomicReference<VBox> secondRoot = new AtomicReference<>();

        FxTestSupport.run(() -> {
            firstRoot.set(new VBox());
            secondRoot.set(new VBox());
            manager.register(new Scene(firstRoot.get()));
            Scene secondScene = new Scene(secondRoot.get());
            manager.register(secondScene);
            manager.register(secondScene);

            AppearancePreferences preview =
                    new AppearancePreferences(AppearanceTheme.MIDNIGHT, FontScale.LARGE, InterfaceDensity.COMPACT);
            manager.preview(preview);
            assertAppearance(firstRoot.get(), preview);
            assertAppearance(secondRoot.get(), preview);
            assertEquals(AppearancePreferences.defaults(), store.value);

            VBox lateRoot = new VBox();
            Stage lateWindow = new Stage();
            lateWindow.setScene(new Scene(lateRoot));
            lateWindow.show();
            assertAppearance(lateRoot, preview);
            lateWindow.hide();

            manager.cancelPreview();
            assertAppearance(firstRoot.get(), AppearancePreferences.defaults());

            AppearancePreferences saved =
                    new AppearancePreferences(AppearanceTheme.HONEY, FontScale.EXTRA_LARGE, InterfaceDensity.SPACIOUS);
            manager.save(saved);
            assertEquals(saved, store.value);
            assertEquals(saved, manager.saved());
            assertEquals(saved, manager.applied());
            assertAppearance(firstRoot.get(), saved);
            assertFalse(firstRoot.get().getStyleClass().contains(AppearanceTheme.MIDNIGHT.cssClass()));
        });
    }

    @Test
    void 损坏持久化枚举回退安全默认值() {
        assertEquals(9, AppearanceTheme.values().length);
        assertEquals(4, FontScale.values().length);
        assertEquals(3, InterfaceDensity.values().length);
        assertEquals(AppearanceTheme.CARBON, AppearanceTheme.fromId("carbon"));
        assertEquals(FontScale.LARGE, FontScale.fromPercent(110));
        assertEquals(InterfaceDensity.COMPACT, InterfaceDensity.fromId("compact"));
        assertEquals(AppearanceTheme.EMERALD, AppearanceTheme.fromId("unknown"));
        assertEquals(FontScale.STANDARD, FontScale.fromPercent(999));
        assertEquals(InterfaceDensity.STANDARD, InterfaceDensity.fromId(null));
    }

    @Test
    void 空存储值和空外观调用会立即失败() {
        assertThrows(NullPointerException.class, () -> new DesktopAppearanceManager(null));
        assertThrows(NullPointerException.class, () -> new DesktopAppearanceManager(new NullStore()));
        DesktopAppearanceManager manager =
                new DesktopAppearanceManager(new MemoryStore(AppearancePreferences.defaults()));
        FxTestSupport.run(() -> {
            assertThrows(NullPointerException.class, () -> manager.register(null));
            assertThrows(NullPointerException.class, () -> manager.preview(null));
            assertThrows(NullPointerException.class, () -> manager.save(null));
            assertThrows(NullPointerException.class, () -> DesktopAppearanceManager.apply(null, manager.saved()));
            assertThrows(NullPointerException.class, () -> DesktopAppearanceManager.apply(new Scene(new VBox()), null));
        });
    }

    private static void assertAppearance(VBox root, AppearancePreferences expected) {
        assertTrue(root.getStyleClass().contains(expected.theme().cssClass()));
        assertTrue(root.getStyleClass().contains(expected.fontScale().cssClass()));
        assertTrue(root.getStyleClass().contains(expected.density().cssClass()));
    }

    private static final class MemoryStore implements AppearancePreferenceStore {
        private AppearancePreferences value;

        private MemoryStore(AppearancePreferences value) {
            this.value = value;
        }

        @Override
        public AppearancePreferences load() {
            return value;
        }

        @Override
        public void save(AppearancePreferences preferences) {
            value = preferences;
        }
    }

    private static final class NullStore implements AppearancePreferenceStore {
        @Override
        public AppearancePreferences load() {
            return null;
        }

        @Override
        public void save(AppearancePreferences preferences) {
            throw new AssertionError("不应保存");
        }
    }
}
