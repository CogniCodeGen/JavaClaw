package com.javaclaw.desktop.component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.appearance.AppearancePreferences;
import com.javaclaw.desktop.appearance.DesktopAppearanceManager;
import com.javaclaw.desktop.appearance.FontScale;
import com.javaclaw.desktop.component.AsyncActionBar.ActionState;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncActionBarLayoutTest {
    @Test
    void 宽屏保持原HBox各节点像素位置且从换行恢复后没有残留高度() {
        FxTestSupport.run(() -> {
            AsyncActionBar actual = new AsyncActionBar(button("丢弃草稿"), button("保存"));
            HBox original = originalBar("已保存");
            actual.show(ActionState.SUCCESS, "已保存");
            VBox root = new VBox(actual, original);
            Scene scene = styled(root, 1100, 200, FontScale.STANDARD);
            layout(scene);
            assertSameLayout(original, actual);
            double width = actual.getWidth();
            actual.resize(180, actual.prefHeight(180));
            actual.layout();
            actual.resize(width, actual.prefHeight(width));
            actual.layout();
            assertSameLayout(original, actual);
        });
    }

    @Test
    void 默认设置窗口八个按钮与状态都完整可见且保留正文空间() {
        FxTestSupport.run(() -> assertManagementLayout(1040, FontScale.STANDARD));
    }

    @Test
    void 窄设置窗口的八个按钮自动换行而非压缩成省略号() {
        FxTestSupport.run(() -> assertManagementLayout(760, FontScale.STANDARD));
    }

    @Test
    void 大字号下按钮和多行状态均完整且动作可执行() {
        FxTestSupport.run(() -> assertManagementLayout(760, FontScale.EXTRA_LARGE));
    }

    @Test
    void 长错误只占三行且完整提示保留在Tooltip而不挤走正文和按钮() {
        FxTestSupport.run(() -> {
            List<Button> buttons = mcpActions();
            AsyncActionBar bar = new AsyncActionBar(buttons.toArray(Node[]::new));
            String message = "无法连接外部工具服务，请检查连接地址和授权状态。\n".repeat(40);
            bar.show(ActionState.ERROR, message);
            ScrollPane body = new ScrollPane(new Label("服务配置正文"));
            body.setMinSize(0, 0);
            ManagementPageShell shell = new ManagementPageShell("设置与管理");
            shell.showPage("外部工具", body);
            shell.setActionContent(Optional.of(bar));
            Scene scene = styled(shell, 760, 720, FontScale.STANDARD);
            layout(scene);
            Label status = (Label) bar.getChildren().get(1);
            assertEquals(message, status.getText());
            assertEquals(message, status.getTooltip().getText());
            assertTrue(status.isTextTruncated());
            assertTrue(status.getHeight() < 60);
            assertTrue(body.getHeight() > 300);
            assertTrue(bar.getHeight() < 240);
            buttons.forEach(button -> {
                assertFalse(button.isTextTruncated(), button.getText());
                assertInsideScene(button, scene);
            });
            bar.show(ActionState.IDLE, null);
            layout(scene);
            assertNull(status.getTooltip());
            assertTrue(body.getHeight() > 300);
        });
    }

    private static void assertManagementLayout(int width, FontScale scale) {
        List<Button> buttons = mcpActions();
        AtomicInteger invoked = new AtomicInteger();
        buttons.forEach(button -> button.setOnAction(event -> invoked.incrementAndGet()));
        AsyncActionBar bar = new AsyncActionBar(buttons.toArray(Node[]::new));
        bar.show(ActionState.PENDING, "正在更新外部工具配置，请稍候。配置完成后可以检查服务状态，刷新工具目录或继续授权。");
        if (scale == FontScale.EXTRA_LARGE) {
            // 按钮与状态有固定字号令牌；显式放大保证本场景实际覆盖更大的文本测量。
            buttons.forEach(button -> button.setStyle("-fx-font-size: 20px;"));
            bar.getChildren().get(1).setStyle("-fx-font-size: 16px;");
        }
        ManagementPageShell shell = new ManagementPageShell("设置与管理");
        ScrollPane body = new ScrollPane(new Label("连接详情\n模型与工具目录\n授权状态"));
        body.setMinSize(0, 0);
        shell.showPage("外部工具", body);
        shell.setActionContent(Optional.of(bar));
        Scene scene = styled(shell, width, 720, scale);
        layout(scene);
        Label status = (Label) bar.getChildren().get(1);
        assertFalse(status.isTextTruncated(), "动作状态不能被按钮挤成省略号");
        assertTrue(status.getHeight() >= status.prefHeight(status.getWidth()) - 1);
        assertTrue(body.getHeight() > 300, "操作栏不能挤占正文的大部分空间");
        assertTrue(bar.getHeight() < 240, "有限按钮集合应保持紧凑的多行排布");
        for (Button button : buttons) {
            assertFalse(button.isTextTruncated(), button.getText());
            assertTrue(button.getWidth() >= button.prefWidth(-1) - 1, button.getText());
            assertInsideScene(button, scene);
            button.fire();
        }
        assertInsideScene(status, scene);
        assertEquals(buttons.size(), invoked.get());
        if (width < 1000) {
            assertTrue(buttons.stream().map(Button::getLayoutY).distinct().count() > 1);
        }
        double bottom = buttons.stream()
                .mapToDouble(button -> button.getLayoutY() + button.getHeight())
                .max()
                .orElseThrow();
        assertTrue(bottom <= bar.getHeight() - bar.getInsets().getBottom() + 1);
    }

    private static void assertSameLayout(HBox expected, HBox actual) {
        assertEquals(expected.getHeight(), actual.getHeight(), 0.01);
        assertEquals(expected.getChildren().size(), actual.getChildren().size());
        for (int index = 0; index < expected.getChildren().size(); index++) {
            Bounds before = expected.getChildren().get(index).getBoundsInParent();
            Bounds after = actual.getChildren().get(index).getBoundsInParent();
            assertEquals(before.getMinX(), after.getMinX(), 0.01, "水平位置 " + index);
            assertEquals(before.getMinY(), after.getMinY(), 0.01, "垂直位置 " + index);
            assertEquals(before.getWidth(), after.getWidth(), 0.01, "宽度 " + index);
            assertEquals(before.getHeight(), after.getHeight(), 0.01, "高度 " + index);
        }
    }

    private static void assertInsideScene(Node node, Scene scene) {
        Bounds bounds = node.localToScene(node.getBoundsInLocal());
        assertTrue(bounds.getMinX() >= -1 && bounds.getMaxX() <= scene.getWidth() + 1, "动作必须在窗口宽度内可达");
        assertTrue(bounds.getMinY() >= -1 && bounds.getMaxY() <= scene.getHeight() + 1, "动作必须在窗口高度内可达");
    }

    private static HBox originalBar(String message) {
        ProgressIndicator progress = new ProgressIndicator();
        progress.setMaxSize(16, 16);
        progress.setVisible(false);
        progress.setManaged(false);
        Label status = new Label(message);
        status.getStyleClass().add("platform-action-status");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(progress, status, spacer, button("丢弃草稿"), button("保存"));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("platform-action-bar");
        return row;
    }

    private static Button button(String text) {
        return new PlatformComponentFactory().action(text, ActionStyle.SOFT, ActionSize.NORMAL);
    }

    private static List<Button> mcpActions() {
        return List.of(
                button("保存"),
                button("丢弃草稿"),
                button("启用"),
                button("健康检查"),
                button("刷新目录"),
                button("启动 OAuth"),
                button("刷新授权状态"),
                button("取消授权"));
    }

    private static Scene styled(javafx.scene.Parent root, int width, int height, FontScale scale) {
        Scene scene = new Scene(root, width, height);
        DesktopStylesheets.apply(scene);
        AppearancePreferences defaults = AppearancePreferences.defaults();
        DesktopAppearanceManager.apply(scene, new AppearancePreferences(defaults.theme(), scale, defaults.density()));
        return scene;
    }

    private static void layout(Scene scene) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
    }
}
