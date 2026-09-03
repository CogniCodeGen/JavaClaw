package com.javaclaw.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedControlContrastTest {
    private static final double MINIMUM_CONTRAST = 4.5;

    @Test
    void 所有主题的表格列表下拉和切换选中文字均清晰可读() {
        FxTestSupport.run(() -> {
            Fixture fixture = fixture();
            try {
                for (AppearanceTheme theme : AppearanceTheme.values()) {
                    AppearancePreferences appearance =
                            new AppearancePreferences(theme, FontScale.STANDARD, InterfaceDensity.STANDARD);
                    DesktopAppearanceManager.apply(fixture.scene(), appearance);
                    fixture.scene().getRoot().applyCss();
                    fixture.scene().getRoot().layout();

                    assertContrast(firstTableCell(fixture.table()), theme, "平台表格");
                    assertContrast(firstListCell(fixture.list()), theme, "普通列表");
                    fixture.toggles()
                            .forEach(toggle -> assertContrast(
                                    toggle, theme, toggle.getStyleClass().toString()));
                    assertComboBoxPopup(fixture.comboBox(), appearance, theme);
                }
            } finally {
                fixture.comboBox().hide();
                fixture.stage().hide();
            }
        });
    }

    private static Fixture fixture() {
        TableView<String> table = new TableView<>(FXCollections.observableArrayList("对话模型"));
        TableColumn<String, String> column = new TableColumn<>("模型");
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue()));
        table.getColumns().add(column);
        table.getStyleClass().add("platform-data-table");
        table.setPrefHeight(100);
        table.getSelectionModel().selectFirst();

        ListView<String> list = new ListView<>(FXCollections.observableArrayList("已选列表项"));
        list.setPrefHeight(76);
        list.getSelectionModel().selectFirst();

        ComboBox<String> comboBox = new ComboBox<>(FXCollections.observableArrayList("已选下拉项", "其他项"));
        comboBox.setValue(comboBox.getItems().getFirst());
        comboBox.setPrefWidth(260);
        PlatformComponentFactory components = new PlatformComponentFactory();
        comboBox.setCellFactory(ignored -> components.textCell(value -> value));
        comboBox.setButtonCell(components.textCell(value -> value));

        List<ToggleButton> toggles = List.of(
                selectedToggle("原生切换"),
                selectedToggle("设置导航", "settings-category-btn"),
                selectedToggle("弹窗导航", "modal-nav-btn"),
                selectedToggle("分段选项", "seg-btn"),
                selectedToggle("管理分段", "platform-segment-button"),
                selectedToggle("会话模式", "jc-mode-chip"));

        VBox root = new VBox(10, table, list, comboBox);
        root.getStyleClass().add("platform-page");
        root.getChildren().addAll(toggles);
        Scene scene = new Scene(root, 560, 620);
        DesktopStylesheets.apply(scene);
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        root.applyCss();
        root.layout();
        return new Fixture(scene, stage, table, list, comboBox, toggles);
    }

    private static ToggleButton selectedToggle(String text, String... styleClasses) {
        ToggleButton toggle = new ToggleButton(text);
        toggle.getStyleClass().addAll(styleClasses);
        toggle.setSelected(true);
        return toggle;
    }

    private static TableCell<?, ?> firstTableCell(TableView<?> table) {
        return table.lookupAll(".table-cell").stream()
                .filter(TableCell.class::isInstance)
                .map(TableCell.class::cast)
                .filter(cell -> cell.getIndex() == 0
                        && cell.getText() != null
                        && !cell.getText().isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到已选表格单元格"));
    }

    private static ListCell<?> firstListCell(ListView<?> list) {
        return list.lookupAll(".list-cell").stream()
                .filter(ListCell.class::isInstance)
                .map(ListCell.class::cast)
                .filter(cell -> cell.getIndex() == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到已选列表单元格"));
    }

    private static void assertComboBoxPopup(
            ComboBox<?> comboBox, AppearancePreferences appearance, AppearanceTheme theme) {
        comboBox.show();
        ListView<?> options = Window.getWindows().stream()
                .filter(PopupWindow.class::isInstance)
                .filter(Window::isShowing)
                .map(Window::getScene)
                .flatMap(scene -> scene.getRoot().lookupAll(".list-view").stream())
                .filter(ListView.class::isInstance)
                .map(ListView.class::cast)
                .filter(candidate -> candidate.getItems().equals(comboBox.getItems()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 ComboBox 弹层"));
        DesktopAppearanceManager.apply(options.getScene(), appearance);
        options.getSelectionModel().selectFirst();
        options.applyCss();
        options.layout();
        assertContrast(firstListCell(options), theme, "ComboBox 弹层");
        comboBox.hide();
    }

    private static void assertContrast(Labeled text, AppearanceTheme theme, String control) {
        Color background = effectiveBackground(text);
        Color foreground = composite(requireColor(text.getTextFill()), background);
        double contrast = contrast(foreground, background);
        assertTrue(
                contrast + 0.001 >= MINIMUM_CONTRAST,
                () -> theme.displayName() + " " + control + " 对比度仅为 " + contrast + "，前景 " + foreground + "，背景 "
                        + background);
    }

    private static Color effectiveBackground(Node node) {
        List<Node> hierarchy = new ArrayList<>();
        for (Node current = node; current != null; current = current.getParent()) {
            hierarchy.add(current);
        }
        Collections.reverse(hierarchy);
        Paint sceneFill = node.getScene().getFill();
        Color result = sceneFill instanceof Color color ? color : Color.WHITE;
        for (Node current : hierarchy) {
            if (current instanceof Region region) {
                result = composite(region.getBackground(), result);
            }
        }
        return result;
    }

    private static Color composite(Background background, Color result) {
        if (background == null) {
            return result;
        }
        Color composed = result;
        for (var fill : background.getFills()) {
            if (fill.getFill() instanceof Color color) {
                composed = composite(color, composed);
            }
        }
        return composed;
    }

    private static Color composite(Color foreground, Color background) {
        double alpha = foreground.getOpacity() + background.getOpacity() * (1.0 - foreground.getOpacity());
        if (alpha == 0) {
            return Color.TRANSPARENT;
        }
        double red = (foreground.getRed() * foreground.getOpacity()
                        + background.getRed() * background.getOpacity() * (1.0 - foreground.getOpacity()))
                / alpha;
        double green = (foreground.getGreen() * foreground.getOpacity()
                        + background.getGreen() * background.getOpacity() * (1.0 - foreground.getOpacity()))
                / alpha;
        double blue = (foreground.getBlue() * foreground.getOpacity()
                        + background.getBlue() * background.getOpacity() * (1.0 - foreground.getOpacity()))
                / alpha;
        return new Color(red, green, blue, alpha);
    }

    private static Color requireColor(Paint paint) {
        if (paint instanceof Color color) {
            return color;
        }
        throw new AssertionError("文字色不是可验证的纯色：" + paint);
    }

    private static double contrast(Color first, Color second) {
        double firstLuminance = luminance(first);
        double secondLuminance = luminance(second);
        return (Math.max(firstLuminance, secondLuminance) + 0.05) / (Math.min(firstLuminance, secondLuminance) + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    private static double linear(double channel) {
        return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private record Fixture(
            Scene scene,
            Stage stage,
            TableView<String> table,
            ListView<String> list,
            ComboBox<String> comboBox,
            List<ToggleButton> toggles) {}
}
