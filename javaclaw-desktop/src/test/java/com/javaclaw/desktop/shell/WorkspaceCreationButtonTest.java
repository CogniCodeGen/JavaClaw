package com.javaclaw.desktop.shell;

import java.io.IOException;
import java.util.List;

import javafx.css.PseudoClass;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.AppearanceTheme;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.appearance.InterfaceDensity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceCreationButtonTest {
    @Test
    void 长名称和最大字号下创建图标仍居中完整显示且不被选择器挤压() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = fixture()) {
                fixture.choices().setPromptText("名称很长的自定义工作区项目".repeat(8));
                for (int width : List.of(168, 248)) {
                    for (FontScale scale : List.of(FontScale.STANDARD, FontScale.EXTRA_LARGE)) {
                        DesktopAppearanceManager.apply(
                                fixture.scene(),
                                new AppearancePreferences(AppearanceTheme.EMERALD, scale, InterfaceDensity.SPACIOUS));
                        fixture.root().resize(width, 64);
                        fixture.root().applyCss();
                        fixture.root().layout();
                        assertCreationBounds(fixture, width);
                    }
                }
            }
        });
    }

    @Test
    void 创建入口有明确说明和可见的悬停按下反馈() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = fixture()) {
                fixture.root().applyCss();
                fixture.root().layout();
                Button button = fixture.button();
                assertEquals("新建工作区", button.getAccessibleText());
                assertTrue(button.getTooltip().getText().contains("设置名称和本地目录"));
                assertTrue(button.isFocusTraversable());
                var before = button.getBackground();
                button.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
                fixture.root().applyCss();
                assertNotEquals(before, button.getBackground());
                var hover = button.getBackground();
                button.pseudoClassStateChanged(PseudoClass.getPseudoClass("pressed"), true);
                fixture.root().applyCss();
                assertNotEquals(hover, button.getBackground());
                assertFalse(button.isTextTruncated());
            }
        });
    }

    private static void assertCreationBounds(Fixture fixture, int width) {
        Button button = fixture.button();
        SVGPath plus = assertInstanceOf(SVGPath.class, button.getGraphic());
        assertEquals(32, button.getWidth(), 0.1);
        assertEquals(32, button.getHeight(), 0.1);
        var iconBounds = button.sceneToLocal(plus.localToScene(plus.getBoundsInLocal()));
        assertEquals(14, iconBounds.getWidth(), 0.1);
        assertEquals(14, iconBounds.getHeight(), 0.1);
        assertEquals(16, iconBounds.getCenterX(), 0.5);
        assertEquals(16, iconBounds.getCenterY(), 0.5);
        assertTrue(assertInstanceOf(Color.class, plus.getFill()).getOpacity() > 0);
        var buttonBounds = button.getBoundsInParent();
        assertTrue(buttonBounds.getMaxX() <= width + 0.5);
        assertTrue(fixture.choices().getBoundsInParent().getMaxX() <= buttonBounds.getMinX() - 5.5);
        assertFalse(button.isTextTruncated(), "创建按钮不能退化成省略号");
    }

    private static Fixture fixture() {
        try {
            FXMLLoader loader = new FXMLLoader(WorkspaceCreationButtonTest.class.getResource("/fxml/main.fxml"));
            loader.load();
            Button button = (Button) loader.getNamespace().get("newWorkspaceButton");
            ComboBox<?> choices = (ComboBox<?>) loader.getNamespace().get("workspaceBox");
            HBox row = (HBox) button.getParent();
            ((VBox) row.getParent()).getChildren().remove(row);
            StackPane root = new StackPane(row);
            root.getStyleClass().add("chat-root");
            Scene scene = new Scene(root, 208, 64);
            DesktopStylesheets.apply(scene);
            return new Fixture(root, scene, button, choices, loader.getController());
        } catch (IOException failure) {
            throw new AssertionError("无法加载实际工作区创建入口", failure);
        }
    }

    /**
     * 仅挂载实际 FXML 中的工作区操作行，不连接服务或展示其他主界面区域。
     *
     * @param root 局部布局根
     * @param scene 局部 Scene
     * @param button 实际创建按钮
     * @param choices 实际工作区选择器
     * @param controller 负责释放初始化监听器
     */
    private record Fixture(
            StackPane root, Scene scene, Button button, ComboBox<?> choices, DesktopShellController controller)
            implements AutoCloseable {
        @Override
        public void close() {
            controller.close();
        }
    }
}
