package com.javaclaw.desktop.shell;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.css.PseudoClass;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.InputMethodHighlight;
import javafx.scene.input.InputMethodTextRun;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ApprovalState;
import com.javaclaw.api.TurnStatus;
import com.javaclaw.desktop.DesktopStylesheets;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.state.ConnectionState;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.state.InputInteractionState;
import com.javaclaw.desktop.state.InteractionState;
import com.javaclaw.desktop.state.ThreadState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellInteractionBehaviorTest {
    @Test
    void 空闲侧区不占布局且同批请求被关闭后不重复弹出() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                assertFalse(fixture.progress.isVisible());
                assertFalse(fixture.progress.isManaged());
                fixture.input.requestFocus();
                fixture.panels.render(pending(true, false));
                assertTrue(fixture.progress.isVisible());
                assertSame(fixture.input, fixture.root.getScene().getFocusOwner());
                assertEquals("待处理 1", fixture.toggle.getText());
                fixture.panels.toggleProgress();
                fixture.panels.render(pending(true, false));
                assertFalse(fixture.progress.isVisible());
                fixture.panels.render(pending(true, true));
                assertTrue(fixture.progress.isVisible());
                assertEquals("待处理 2", fixture.toggle.getText());
                fixture.panels.render(pending(false, false));
                assertFalse(fixture.progress.isManaged());
                assertEquals("待处理", fixture.toggle.getText());
            }
        });
    }

    @Test
    void 新审批不替换文档且用户主动打开的空面板保留() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                AtomicInteger pendingSelected = new AtomicInteger();
                fixture.panels.onPending(pendingSelected::incrementAndGet);
                fixture.panels.openDocument();
                fixture.panels.render(pending(true, false));
                assertEquals(0, pendingSelected.get());
                assertTrue(fixture.progress.isVisible());
                fixture.panels.documentClosed();
                assertTrue(fixture.progress.isVisible());
                fixture.panels.render(pending(false, false));
                assertTrue(fixture.progress.isVisible());
                fixture.panels.toggleProgress();
                fixture.panels.toggleProgress();
                fixture.panels.render(pending(false, false));
                assertTrue(fixture.progress.isVisible());
                fixture.panels.openDocument();
                fixture.panels.documentClosed();
                assertFalse(fixture.progress.isVisible());
            }
        });
    }

    @Test
    void 输入请求读取错误只提醒一次且恢复后自动收起() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                DesktopState base = DesktopState.initial();
                var inputs = new InputInteractionState(List.of(), Optional.empty(), Optional.of("请重试读取"), 1);
                var interaction =
                        new InteractionState(List.of(), Optional.empty(), List.of(), inputs, false, Optional.empty());
                DesktopState failed = new DesktopState(
                        base.connection(), base.navigation(), base.threads(), base.transcript(), interaction);
                fixture.panels.render(failed);
                assertTrue(fixture.progress.isVisible());
                fixture.panels.render(failed);
                assertTrue(fixture.progress.isVisible());
                fixture.panels.render(base);
                assertFalse(fixture.progress.isVisible());
                fixture.panels.render(failed);
                fixture.panels.toggleProgress();
                fixture.panels.render(failed);
                assertFalse(fixture.progress.isVisible());
            }
        });
    }

    @Test
    void 窄窗按需让出导航并在右区关闭或窗口变宽后恢复偏好() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.root.resize(940, 640);
                fixture.panels.render(pending(true, false));
                assertFalse(fixture.sidebar.isManaged());
                assertTrue(fixture.progress.isManaged());
                fixture.root.resize(1280, 820);
                assertTrue(fixture.sidebar.isManaged());
                fixture.root.resize(940, 640);
                fixture.panels.toggleSidebar();
                assertTrue(fixture.sidebar.isManaged());
                assertFalse(fixture.progress.isManaged());
                fixture.panels.toggleSidebar();
                fixture.panels.toggleProgress();
                fixture.panels.toggleProgress();
                assertFalse(fixture.sidebar.isManaged());
                fixture.root.resize(1280, 820);
                assertFalse(fixture.sidebar.isManaged());
            }
        });
    }

    @Test
    void Escape关闭当前侧区并返回触发焦点且不影响输入内容() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.input.setText("保留草稿");
                fixture.input.requestFocus();
                fixture.panels.toggleProgress();
                fixture.inside.requestFocus();
                Event.fireEvent(fixture.inside, key(KeyCode.ESCAPE, false));
                assertFalse(fixture.progress.isVisible());
                assertSame(fixture.input, fixture.root.getScene().getFocusOwner());
                assertEquals("保留草稿", fixture.input.getText());
                fixture.panels.toggleProgress();
                fixture.input.requestFocus();
                Event.fireEvent(fixture.input, key(KeyCode.ESCAPE, false));
                assertFalse(fixture.progress.isVisible());
                assertEquals("保留草稿", fixture.input.getText());
            }
        });
    }

    @Test
    void 自动展开后Escape恢复原草稿焦点并保留后来选择的草稿焦点() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.input.setText("保留草稿");
                fixture.input.requestFocus();
                fixture.panels.render(pending(true, false));
                fixture.inside.requestFocus();
                Event.fireEvent(fixture.inside, key(KeyCode.ESCAPE, false));
                assertFalse(fixture.progress.isVisible());
                assertSame(fixture.input, fixture.root.getScene().getFocusOwner());

                fixture.panels.render(pending(false, false));
                fixture.toggle.requestFocus();
                fixture.panels.render(pending(true, false));
                fixture.input.requestFocus();
                Event.fireEvent(fixture.input, key(KeyCode.ESCAPE, false));
                assertFalse(fixture.progress.isVisible());
                assertSame(fixture.input, fixture.root.getScene().getFocusOwner());
                assertEquals("保留草稿", fixture.input.getText());
            }
        });
    }

    @Test
    void 已完成已取消和断线状态不显示正在执行并保留文字说明() {
        FxTestSupport.run(() -> {
            Label meta = new Label();
            Label dot = new Label();
            var labels =
                    new ShellStatusLabels(new Label(), new Label(), meta, new Label(), new VBox(), new Label(), dot);
            ConnectionState connected = ConnectionState.connected("连接成功", DesktopTestFixtures.NOW);
            labels.render(turnState(connected, TurnStatus.RUNNING));
            assertEquals("执行中", meta.getText());
            assertTrue(dot.getStyleClass().contains("status-executing"));
            labels.render(turnState(connected, TurnStatus.COMPLETED));
            assertEquals("已完成", meta.getText());
            assertTrue(dot.getStyleClass().contains("status-idle"));
            labels.render(turnState(connected, TurnStatus.CANCELLED));
            assertEquals("已停止", meta.getText());
            assertTrue(dot.getStyleClass().contains("status-idle"));
            labels.render(turnState(connected, TurnStatus.FAILED));
            assertEquals("执行失败", meta.getText());
            assertTrue(dot.getStyleClass().contains("status-failed"));
            labels.render(turnState(connected, TurnStatus.WAITING));
            assertEquals("等待处理", meta.getText());
            assertTrue(dot.getStyleClass().contains("status-waiting"));
            labels.render(turnState(ConnectionState.failed("服务已断开"), TurnStatus.RUNNING));
            assertTrue(dot.getStyleClass().contains("status-failed"));
            assertEquals("服务已断开", dot.getAccessibleText());
            assertEquals("服务连接失败", meta.getText());
            labels.render(turnState(ConnectionState.connecting(), TurnStatus.RUNNING));
            assertTrue(dot.getStyleClass().contains("status-waiting"));
            labels.render(turnState(ConnectionState.disconnected(), TurnStatus.RUNNING));
            assertTrue(dot.getStyleClass().contains("status-idle"));
        });
    }

    @Test
    void 尚无任务时顶部仍优先说明连接状态() {
        FxTestSupport.run(() -> {
            Label meta = new Label();
            Label dot = new Label();
            var labels =
                    new ShellStatusLabels(new Label(), new Label(), meta, new Label(), new VBox(), new Label(), dot);
            DesktopState base = DesktopState.initial();
            labels.render(base);
            assertEquals("服务未连接", meta.getText());
            for (ConnectionState connection : List.of(ConnectionState.connecting(), ConnectionState.failed("离线"))) {
                labels.render(new DesktopState(
                        connection, base.navigation(), base.threads(), base.transcript(), base.interaction()));
                assertEquals(
                        connection.status() == ConnectionState.Status.CONNECTING ? "正在连接服务…" : "服务连接失败",
                        meta.getText());
            }
        });
    }

    private static DesktopState turnState(ConnectionState connection, TurnStatus status) {
        var workspace = DesktopTestFixtures.workspace();
        var thread = DesktopTestFixtures.thread();
        ThreadState threads = new ThreadState(
                List.of(workspace),
                Optional.of(workspace),
                List.of(thread),
                Optional.of(thread),
                Optional.of(DesktopTestFixtures.turn(thread, status, 1)));
        DesktopState base = DesktopState.initial();
        return new DesktopState(connection, base.navigation(), threads, base.transcript(), base.interaction());
    }

    @Test
    void 输入按内容长高且清空收回两行并保留组合输入发送边界() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                AtomicInteger sent = new AtomicInteger();
                AtomicInteger changes = new AtomicInteger();
                fixture.send.setOnAction(event -> sent.incrementAndGet());
                try (var composer = new ShellComposerBehavior(
                        fixture.input, fixture.card, fixture.send, changes::incrementAndGet)) {
                    assertEquals(60, fixture.input.getPrefHeight());
                    fixture.input.setText("多行输入\n".repeat(30));
                    assertEquals(240, fixture.input.getPrefHeight());
                    fixture.input.clear();
                    assertEquals(60, fixture.input.getPrefHeight());
                    fixture.input.setText("x".repeat(5000));
                    assertEquals(240, fixture.input.getPrefHeight());
                    fixture.input.clear();
                    fixture.input.requestFocus();
                    Event.fireEvent(fixture.input, composition("zhong", ""));
                    Event.fireEvent(fixture.input, key(KeyCode.ENTER, true));
                    assertEquals(0, sent.get());
                    Event.fireEvent(fixture.input, composition("", "中"));
                    Event.fireEvent(fixture.input, key(KeyCode.ENTER, true));
                    assertEquals(1, sent.get());
                    fixture.send.setDisable(true);
                    Event.fireEvent(fixture.input, key(KeyCode.ENTER, true));
                    assertEquals(1, sent.get());
                    assertTrue(changes.get() >= 4);
                }
            }
        });
    }

    @Test
    void 输入焦点由卡片显示且Enter发送不追加换行() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                AtomicInteger sent = new AtomicInteger();
                fixture.send.setOnAction(event -> sent.incrementAndGet());
                try (var composer = new ShellComposerBehavior(fixture.input, fixture.card, fixture.send, () -> {})) {
                    fixture.input.requestFocus();
                    fixture.root.applyCss();
                    fixture.input.setText("准备发送的消息");
                    Event.fireEvent(fixture.input, key(KeyCode.ENTER, false));
                    assertEquals(1, sent.get());
                    assertEquals("准备发送的消息", fixture.input.getText());
                    // 场景焦点保证可访问入口；窗口失焦的平台不强行模拟操作系统激活。
                    if (fixture.input.isFocused()) {
                        assertTrue(fixture.card
                                .getPseudoClassStates()
                                .contains(PseudoClass.getPseudoClass("composer-focused")));
                    }
                }
            }
        });
    }

    @Test
    void Shift回车替换选区换行且支持撤销并且不触发发送() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                AtomicInteger sent = new AtomicInteger();
                fixture.send.setOnAction(event -> sent.incrementAndGet());
                try (var composer = new ShellComposerBehavior(fixture.input, fixture.card, fixture.send, () -> {})) {
                    fixture.input.requestFocus();
                    fixture.root.applyCss();
                    fixture.input.setText("前文选区后文");
                    fixture.input.selectRange(2, 4);
                    Event.fireEvent(fixture.input, key(KeyCode.ENTER, true, false, false, false));
                    assertEquals("前文\n后文", fixture.input.getText());
                    assertEquals(3, fixture.input.getCaretPosition());
                    assertEquals(0, sent.get());
                    assertTrue(fixture.input.isUndoable());
                    fixture.input.undo();
                    assertEquals("前文选区后文", fixture.input.getText());
                    assertEquals(0, sent.get());
                }
            }
        });
    }

    @Test
    void 中文组合输入期间回车和Shift回车均不发送也不追加换行() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                AtomicInteger sent = new AtomicInteger();
                fixture.send.setOnAction(event -> sent.incrementAndGet());
                try (var composer = new ShellComposerBehavior(fixture.input, fixture.card, fixture.send, () -> {})) {
                    fixture.input.requestFocus();
                    fixture.root.applyCss();
                    fixture.input.setText("保留草稿");
                    fixture.input.positionCaret(fixture.input.getLength());
                    Event.fireEvent(fixture.input, composition("zhong", ""));
                    String composingDraft = fixture.input.getText();
                    Event.fireEvent(fixture.input, key(KeyCode.ENTER, false));
                    Event.fireEvent(fixture.input, key(KeyCode.ENTER, true, false, false, false));
                    assertEquals(0, sent.get());
                    assertEquals(composingDraft, fixture.input.getText());
                    Event.fireEvent(fixture.input, composition("", "中"));
                    String committedDraft = fixture.input.getText();
                    Event.fireEvent(fixture.input, key(KeyCode.ENTER, false));
                    assertEquals(1, sent.get());
                    assertEquals(committedDraft, fixture.input.getText());
                    assertTrue(committedDraft.endsWith("中"));
                }
            }
        });
    }

    @Test
    void 发送按钮禁用或隐藏时回车不发送且仍可用Shift回车编辑草稿() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                AtomicInteger sent = new AtomicInteger();
                fixture.send.setOnAction(event -> sent.incrementAndGet());
                try (var composer = new ShellComposerBehavior(fixture.input, fixture.card, fixture.send, () -> {})) {
                    fixture.input.requestFocus();
                    fixture.root.applyCss();
                    fixture.input.setText("保留草稿");
                    fixture.input.positionCaret(fixture.input.getLength());
                    fixture.send.setDisable(true);
                    Event.fireEvent(fixture.input, key(KeyCode.ENTER, false));
                    assertEquals(0, sent.get());
                    assertEquals("保留草稿", fixture.input.getText());
                    fixture.send.setDisable(false);
                    fixture.send.setVisible(false);
                    Event.fireEvent(fixture.input, key(KeyCode.ENTER, false));
                    assertEquals(0, sent.get());
                    assertEquals("保留草稿", fixture.input.getText());
                    Event.fireEvent(fixture.input, key(KeyCode.ENTER, true, false, false, false));
                    assertEquals("保留草稿\n", fixture.input.getText());
                    assertEquals(0, sent.get());
                }
            }
        });
    }

    @Test
    void Ctrl和Command单独发送且各自遵守组合输入和禁用边界() {
        FxTestSupport.run(() -> {
            try (Fixture fixture = new Fixture()) {
                AtomicInteger sent = new AtomicInteger();
                fixture.send.setOnAction(event -> sent.incrementAndGet());
                try (var composer = new ShellComposerBehavior(fixture.input, fixture.card, fixture.send, () -> {})) {
                    fixture.input.requestFocus();
                    int expected = 0;
                    for (boolean control : List.of(true, false)) {
                        fixture.send.setDisable(false);
                        Event.fireEvent(fixture.input, composition("zhong", ""));
                        Event.fireEvent(fixture.input, key(KeyCode.ENTER, control, !control));
                        assertEquals(expected, sent.get());
                        Event.fireEvent(fixture.input, composition("", "中"));
                        Event.fireEvent(fixture.input, key(KeyCode.ENTER, control, !control));
                        assertEquals(++expected, sent.get());
                        fixture.send.setDisable(true);
                        Event.fireEvent(fixture.input, key(KeyCode.ENTER, control, !control));
                        assertEquals(expected, sent.get());
                    }
                }
            }
        });
    }

    private static InputMethodEvent composition(String composing, String committed) {
        List<InputMethodTextRun> runs = composing.isEmpty()
                ? List.of()
                : List.of(new InputMethodTextRun(composing, InputMethodHighlight.UNSELECTED_RAW));
        return new InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, runs, committed, 0);
    }

    private static KeyEvent key(KeyCode code, boolean control) {
        return key(code, control, false);
    }

    private static KeyEvent key(KeyCode code, boolean control, boolean meta) {
        return key(code, false, control, false, meta);
    }

    private static KeyEvent key(KeyCode code, boolean shift, boolean control, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, meta);
    }

    private static DesktopState pending(boolean approval, boolean input) {
        DesktopState base = DesktopState.initial();
        var inputs = new InputInteractionState(
                input ? List.of(DesktopTestFixtures.input()) : List.of(), Optional.empty(), Optional.empty(), 0);
        var interactions = new InteractionState(
                List.of(),
                Optional.empty(),
                approval ? List.of(DesktopTestFixtures.approval(ApprovalState.PENDING)) : List.of(),
                inputs,
                false,
                Optional.empty());
        return new DesktopState(base.connection(), base.navigation(), base.threads(), base.transcript(), interactions);
    }

    private static final class Fixture implements AutoCloseable {
        private final BorderPane root = new BorderPane();
        private final VBox sidebar = new VBox();
        private final VBox progress = new VBox();
        private final Button toggle = new Button("待处理");
        private final Button inside = new Button("待处理表单");
        private final TextArea input = new TextArea();
        private final Button send = new Button("发送");
        private final VBox card = new VBox(input, send);
        private final ShellSidePanels panels = new ShellSidePanels(root, sidebar, progress, toggle);
        private final Stage stage = new Stage();

        private Fixture() {
            sidebar.setPrefWidth(240);
            progress.setPrefWidth(340);
            progress.getChildren().add(inside);
            card.getStyleClass().add("composer-card");
            input.getStyleClass().add("composer-input");
            root.setLeft(sidebar);
            root.setRight(progress);
            root.setCenter(new VBox(toggle, card));
            Scene scene = new Scene(root, 1280, 820);
            DesktopStylesheets.apply(scene);
            stage.setScene(scene);
            stage.show();
        }

        @Override
        public void close() {
            panels.close();
            stage.hide();
        }
    }
}
