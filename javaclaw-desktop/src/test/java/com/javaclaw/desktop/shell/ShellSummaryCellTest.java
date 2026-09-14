package com.javaclaw.desktop.shell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.state.OutgoingMessage;
import com.javaclaw.desktop.view.PresentedItem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellSummaryCellTest {
    @Test
    void 工具详情默认折叠且单元格回收后仍保留展开状态() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture();
            var row = new ShellTranscriptRow(
                    "tool-one",
                    new PresentedItem(
                            "工具 · search · 成功", "", "transcript-execution-block", "调用 ID：one\n{\"tools\":[]}", true),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    1);
            fixture.show(row);
            assertNull(fixture.details(), "未展开的工具行不应创建 TextArea");
            fixture.toggle().fire();
            TextArea details = fixture.details();
            assertTrue(details.isVisible());
            assertTrue(details.getText().contains("tools"));
            fixture.cell.updateItem(null, true);
            assertNull(fixture.details(), "回收单元格时应释放详情控件");
            fixture.show(row);
            assertTrue(fixture.details().isVisible());
            assertEquals("收起详情", fixture.toggle().getText());
            fixture.toggle().fire();
            assertNull(fixture.details(), "收起详情后不应留有嵌套滚动区域");
        });
    }

    @Test
    void 原生失败操作受就绪和草稿状态限制并只传当前发送身份() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture();
            var outgoing =
                    new OutgoingMessage("outgoing:one", "原文", Optional.empty(), OutgoingMessage.Status.UNCONFIRMED);
            fixture.show(new ShellTranscriptRow(
                    "outgoing:one",
                    new PresentedItem("你 · 发送未确认", "原文", "message-user"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(outgoing),
                    1));
            assertNull(fixture.details(), "普通消息不创建技术详情控件");
            fixture.retry.set(false);
            fixture.restore.set(false);
            fixture.button("重试").fire();
            fixture.button("恢复到输入框").fire();
            assertTrue(fixture.actions.isEmpty());
            fixture.button("复制原文").fire();
            fixture.retry.set(true);
            fixture.restore.set(true);
            fixture.button("重试").fire();
            fixture.button("恢复到输入框").fire();
            assertEquals(
                    List.of("copySend:outgoing:one", "retrySend:outgoing:one", "restoreSend:outgoing:one"),
                    fixture.actions);
        });
    }

    @Test
    void 长工具详情不切开代理对并明确提示截断限制() {
        FxTestSupport.run(() -> {
            Fixture fixture = new Fixture();
            fixture.show(new ShellTranscriptRow(
                    "long-tool",
                    new PresentedItem("工具结果", "失败原因", "transcript-execution-block", "a".repeat(65_535) + "😀尾部", true),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    1));
            fixture.toggle().fire();
            assertEquals(
                    "a".repeat(65_535) + "\n[内容较长，当前显示已截断]", fixture.details().getText());
        });
    }

    private static final class Fixture {
        private final HashSet<String> expanded = new HashSet<>();
        private final List<String> actions = new ArrayList<>();
        private final SimpleBooleanProperty retry = new SimpleBooleanProperty(true);
        private final SimpleBooleanProperty restore = new SimpleBooleanProperty(true);
        private final ShellSummaryCell cell = new ShellSummaryCell(
                ignored -> {},
                Optional::empty,
                expanded::contains,
                id -> {
                    if (!expanded.remove(id)) {
                        expanded.add(id);
                    }
                },
                (action, id) -> actions.add(action + ":" + id),
                retry,
                restore);
        private final Scene scene = new Scene(new VBox(cell), 800, 600);

        private Fixture() {
            DesktopStylesheets.apply(scene);
        }

        private void show(ShellTranscriptRow row) {
            cell.updateItem(row, false);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        }

        private Hyperlink toggle() {
            return (Hyperlink) cell.lookup(".tool-details-toggle");
        }

        private TextArea details() {
            return (TextArea) cell.lookup(".tool-details-body");
        }

        private Button button(String label) {
            return cell.lookupAll(".button").stream()
                    .filter(Button.class::isInstance)
                    .map(Button.class::cast)
                    .filter(button -> button.getText().equals(label))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
