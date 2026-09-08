package com.javaclaw.desktop;

import java.util.Comparator;
import java.util.List;

import javafx.collections.FXCollections;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.Background;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.appearance.InterfaceDensity;
import com.javaclaw.desktop.component.PlatformComponentFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComboBoxPopupStyleTest {
    @Test
    void 所有主题字号密度和下拉语义都使用不透明弹层且选项不重叠() {
        Fixture fixture = FxTestSupport.call(ComboBoxPopupStyleTest::fixture);
        try {
            for (AppearanceTheme theme : AppearanceTheme.values()) {
                for (InterfaceDensity density : InterfaceDensity.values()) {
                    for (FontScale fontScale : FontScale.values()) {
                        AppearancePreferences preferences = new AppearancePreferences(theme, fontScale, density);
                        // 调度期限约束单个外观场景；完整矩阵不应占住一个 FX Runnable，且每个原始断言仍执行。
                        FxTestSupport.run(() -> {
                            DesktopAppearanceManager.apply(fixture.scene(), preferences);
                            fixture.choices().forEach(choice -> assertPopup(choice, preferences));
                        });
                    }
                }
            }
        } finally {
            FxTestSupport.run(() -> {
                fixture.choices().forEach(ComboBox::hide);
                fixture.stage().hide();
            });
        }
    }

    private static Fixture fixture() {
        List<ComboBox<String>> choices = choices();
        VBox root = new VBox(36);
        root.getChildren().addAll(choices);
        Scene scene = new Scene(root, 520, 520);
        DesktopStylesheets.apply(scene);
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        return new Fixture(choices, scene, stage);
    }

    private static List<ComboBox<String>> choices() {
        ComboBox<String> generic = choice("OpenAI 兼容接口", "Anthropic", "Google Gemini", "OpenAI Responses 接口");
        ComboBox<String> settings = choice("计划", "循环任务", "工作流");
        settings.getStyleClass().add("settings-combo");
        PlatformComponentFactory components = new PlatformComponentFactory();
        settings.setCellFactory(ignored -> components.detailCell(value -> value, value -> "说明 " + value));
        settings.setButtonCell(textCell());
        ComboBox<String> sidebar = choice("工作区一", "工作区二", "工作区三");
        sidebar.getStyleClass().add("sidebar-workspace-combo");
        ComboBox<String> composer = choice("默认智能体", "代码审查", "架构设计");
        composer.getStyleClass().add("composer-select");
        composer.setCellFactory(ignored -> textCell());
        composer.setButtonCell(textCell());
        return List.of(generic, settings, sidebar, composer);
    }

    private static ComboBox<String> choice(String... values) {
        ComboBox<String> choice = new ComboBox<>(FXCollections.observableArrayList(values));
        choice.setValue(values[0]);
        choice.setPrefWidth(360);
        return choice;
    }

    private static ListCell<String> textCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        };
    }

    private static void assertPopup(ComboBox<?> choice, AppearancePreferences preferences) {
        choice.show();
        ListView<?> options = popupOptions(choice);
        DesktopAppearanceManager.apply(options.getScene(), preferences);
        options.applyCss();
        options.layout();

        assertTrue(hasOpaqueFill(options.getBackground()), "ComboBox 弹层必须遮住宿主页面");
        assertTrue(
                options.getBorder() != null && !options.getBorder().getStrokes().isEmpty(), "ComboBox 弹层必须有边界");
        List<Region> rows = options.lookupAll(".list-cell").stream()
                .filter(Region.class::isInstance)
                .map(Region.class::cast)
                .filter(row -> row.isVisible() && row.getHeight() > 0)
                .sorted(Comparator.comparingDouble(
                        row -> row.getBoundsInParent().getMinY()))
                .toList();
        assertFalse(rows.isEmpty(), "ComboBox 弹层应渲染选项行");
        for (int index = 1; index < rows.size(); index++) {
            double previousBottom = rows.get(index - 1).getBoundsInParent().getMaxY();
            double currentTop = rows.get(index).getBoundsInParent().getMinY();
            assertTrue(previousBottom <= currentTop + 0.01, "ComboBox 选项行不得相互覆盖");
        }
        choice.hide();
    }

    private static ListView<?> popupOptions(ComboBox<?> choice) {
        return Window.getWindows().stream()
                .filter(PopupWindow.class::isInstance)
                .filter(Window::isShowing)
                .map(Window::getScene)
                .flatMap(scene -> scene.getRoot().lookupAll(".list-view").stream())
                .filter(ListView.class::isInstance)
                .map(ListView.class::cast)
                .filter(candidate -> candidate.getItems().equals(choice.getItems()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 ComboBox 弹层 ListView"));
    }

    private static boolean hasOpaqueFill(Background background) {
        return background != null
                && background.getFills().stream().map(fill -> fill.getFill()).anyMatch(ComboBoxPopupStyleTest::opaque);
    }

    private static boolean opaque(Paint paint) {
        return paint instanceof Color color && color.getOpacity() == 1.0;
    }

    private record Fixture(List<ComboBox<String>> choices, Scene scene, Stage stage) {}
}
