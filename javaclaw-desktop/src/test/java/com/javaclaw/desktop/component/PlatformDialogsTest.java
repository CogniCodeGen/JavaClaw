package com.javaclaw.desktop.component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformDialogsTest {
    private static final String EXPECTED = "我确认本次模型验证可能产生费用";
    private static final Pattern DIALOG_CONSTRUCTION =
            Pattern.compile("new\\s+(?:Alert|TextInputDialog)\\s*\\(|new\\s+Dialog\\s*<|extends\\s+Dialog\\s*<");

    @Test
    void 精确确认说明输入用途并只在逐字匹配后允许操作() {
        FxTestSupport.run(() -> {
            VBox owner = owner();
            TextInputDialog dialog =
                    PlatformDialogs.exactText(owner, "确认可能计费的模型验证", "会发送一次真实测试对话，可能产生费用", EXPECTED, "验证对话模型");

            DialogPane pane = dialog.getDialogPane();
            VBox body = assertInstanceOf(VBox.class, pane.getContent());
            List<String> messages = body.getChildren().stream()
                    .filter(Label.class::isInstance)
                    .map(Label.class::cast)
                    .map(Label::getText)
                    .toList();
            Button action = (Button) pane.lookupButton(ButtonType.OK);

            assertTrue(messages.stream().anyMatch(value -> value.contains("逐字输入")));
            assertTrue(messages.contains(EXPECTED));
            assertTrue(messages.stream().anyMatch(value -> value.contains("大小写、空格和标点")));
            assertEquals("在此逐字输入上方确认语句", dialog.getEditor().getPromptText());
            assertEquals("验证对话模型", action.getText());
            assertTrue(action.isDisable());

            dialog.getEditor().setText(EXPECTED + " ");
            assertTrue(action.isDisable());
            dialog.getEditor().setText(EXPECTED);
            assertFalse(action.isDisable());
            assertPlatformStyle(dialog);
        });
    }

    @Test
    void 普通输入说明值的用途并阻止空白提交() {
        FxTestSupport.run(() -> {
            TextInputDialog dialog =
                    PlatformDialogs.requiredText(owner(), "创建对话", "设置对话标题", "标题用于在列表中识别任务。", "例如：排查连接", "", "创建对话");
            Button action = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);

            assertEquals("例如：排查连接", dialog.getEditor().getPromptText());
            assertEquals("标题用于在列表中识别任务。", dialog.getEditor().getAccessibleText());
            assertTrue(action.isDisable());
            dialog.getEditor().setText("   ");
            assertTrue(action.isDisable());
            dialog.getEditor().setText("排查连接");
            assertFalse(action.isDisable());
            assertPlatformStyle(dialog);
        });
    }

    @Test
    void 提示和自定义弹窗移除系统图标并为正文提供滚动边界() {
        FxTestSupport.run(() -> {
            VBox owner = owner();
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "长文本说明", ButtonType.CANCEL, ButtonType.OK);
            alert.setGraphic(new Label("?"));
            PlatformDialogs.style(alert, owner);
            ScrollPane messageScroll =
                    assertInstanceOf(ScrollPane.class, alert.getDialogPane().getContent());

            assertNull(alert.getGraphic());
            assertInstanceOf(Label.class, messageScroll.getContent());
            assertPlatformStyle(alert);

            Dialog<Void> custom = new Dialog<>();
            custom.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            VBox content = new VBox(new Label("自定义表单"));
            custom.getDialogPane().setContent(content);
            PlatformDialogs.style(custom, owner);

            ScrollPane bodyScroll =
                    assertInstanceOf(ScrollPane.class, custom.getDialogPane().getContent());
            assertEquals(content, bodyScroll.getContent());
            assertPlatformStyle(custom);
        });
    }

    @Test
    void 所有JavaFx弹窗构造入口都经过平台样式层() throws Exception {
        Path sourceRoot = Path.of(System.getProperty("basedir", "."), "src/main/java");
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<Path> unstyled = paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("PlatformDialogs.java"))
                    .filter(PlatformDialogsTest::constructsDialog)
                    .filter(path -> !contains(path, "PlatformDialogs."))
                    .toList();
            assertTrue(unstyled.isEmpty(), () -> "以下弹窗未接入 PlatformDialogs：" + unstyled);
        }
    }

    private static VBox owner() {
        VBox owner = new VBox();
        new Scene(owner);
        DesktopStylesheets.apply(owner.getScene());
        owner.getStyleClass().removeIf(PlatformDialogsTest::isAppearanceClass);
        owner.getStyleClass().addAll("theme-sapphire", "font-scale-110", "density-spacious");
        return owner;
    }

    private static void assertPlatformStyle(Dialog<?> dialog) {
        DialogPane pane = dialog.getDialogPane();
        assertTrue(pane.getStyleClass()
                .containsAll(
                        List.of("root", "jc-dialog-pane", "theme-sapphire", "font-scale-110", "density-spacious")));
        assertTrue(pane.getStylesheets().stream().anyMatch(value -> value.endsWith("/css/interaction-overlays.css")));
        assertNull(dialog.getGraphic());
    }

    private static boolean constructsDialog(Path path) {
        return DIALOG_CONSTRUCTION.matcher(read(path)).find();
    }

    private static boolean contains(Path path, String expected) {
        return read(path).contains(expected);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException failure) {
            throw new AssertionError("无法读取源码：" + path, failure);
        }
    }

    private static boolean isAppearanceClass(String value) {
        return value.startsWith("theme-") || value.startsWith("font-scale-") || value.startsWith("density-");
    }
}
