package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferenceStore;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.appearance.InterfaceDensity;
import com.javaclaw.desktop.component.PlatformStylesheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModelPickerPopupStyleTest {
    @Test
    void 模型菜单首次打开和切换外观后重开均解析令牌且继承当前预览() {
        try (CssWarnings warnings = new CssWarnings()) {
            Fixture fixture = FxTestSupport.call(ChatModelPickerPopupStyleTest::fixture);
            try {
                for (AppearanceTheme theme : AppearanceTheme.values()) {
                    AppearancePreferences preferences = new AppearancePreferences(
                            theme,
                            FontScale.values()[theme.ordinal() % FontScale.values().length],
                            InterfaceDensity.values()[theme.ordinal() % InterfaceDensity.values().length]);
                    FxTestSupport.run(() -> assertPopup(fixture, preferences));
                }
                assertTrue(warnings.messages.isEmpty(), () -> "模型菜单不能出现令牌解析错误：" + warnings.messages);
            } finally {
                FxTestSupport.run(() -> {
                    fixture.picker().close();
                    fixture.stage().hide();
                });
            }
        }
    }

    private static Fixture fixture() {
        ChatModelPicker picker = new ChatModelPicker(ignored -> {}, () -> {}, () -> {}, () -> {}, () -> {});
        picker.render(
                List.of(TestCoreSettingsFixtures.provider(
                        1, TestCoreSettingsFixtures.providerSpec(Optional.empty()), ProviderLifecycle.ACTIVE)),
                Optional.empty(),
                false);
        Label body = new Label("正文色基准");
        TextField input = new TextField();
        VBox root = new VBox(24, body, input, picker);
        Scene scene = new Scene(root, 560, 500);
        PlatformStylesheets.apply(scene);
        DesktopAppearanceManager appearance = new DesktopAppearanceManager(new PreviewStore());
        appearance.register(scene);
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        return new Fixture(picker, appearance, stage, body, input);
    }

    private static void assertPopup(Fixture fixture, AppearancePreferences preferences) {
        // 与真实外观预览同路：新 Popup 会由窗口监听读取 applied，不能仅静态修改 owner 的 CSS class。
        fixture.appearance().preview(preferences);
        fixture.picker().fire();
        ContextMenu menu = Window.getWindows().stream()
                .filter(ContextMenu.class::isInstance)
                .map(ContextMenu.class::cast)
                .filter(candidate -> candidate.getOwnerNode() == fixture.picker())
                .filter(Window::isShowing)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到聊天模型菜单"));
        var root = menu.getScene().getRoot();
        root.applyCss();
        root.layout();
        assertTrue(root.getStyleClass()
                .containsAll(List.of(
                        preferences.theme().cssClass(),
                        preferences.fontScale().cssClass(),
                        preferences.density().cssClass())));
        TextField search = (TextField) root.lookup(".text-field");
        assertEquals(fixture.input().getFont().getSize(), search.getFont().getSize(), 0.01);
        assertTrue(hasOpaqueFill(search.getBackground()), "搜索框必须解析不透明表面令牌");
        ListView<?> choices = (ListView<?>) root.lookup(".list-view");
        assertFalse(choices.getBorder().getStrokes().isEmpty(), "模型列表必须保留可见边界");
        ListCell<?> row = choices.lookupAll(".list-cell").stream()
                .filter(ListCell.class::isInstance)
                .map(ListCell.class::cast)
                .filter(cell -> !cell.isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("模型目录必须正常显示"));
        assertEquals(fixture.body().getTextFill(), row.getTextFill(), "模型名称应继承当前主题正文色");
        Region surface = (Region) menu.getSkin().getNode();
        assertTrue(hasOpaqueFill(surface.getBackground()), "菜单表面必须不透明");
        menu.hide();
    }

    private static boolean hasOpaqueFill(Background background) {
        return background != null
                && background.getFills().stream()
                        .anyMatch(fill -> fill.getFill() instanceof Color color && color.getOpacity() == 1.0);
    }

    private record Fixture(
            ChatModelPicker picker, DesktopAppearanceManager appearance, Stage stage, Label body, TextField input) {}

    private static final class PreviewStore implements AppearancePreferenceStore {
        @Override
        public AppearancePreferences load() {
            return AppearancePreferences.defaults();
        }

        @Override
        public void save(AppearancePreferences preferences) {
            throw new AssertionError("弹层样式测试只预览，不得保存用户偏好");
        }
    }

    private static final class CssWarnings extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger("javafx.css");
        private final List<String> messages = new ArrayList<>();

        private CssWarnings() {
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                messages.add(record.getMessage());
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }
}
