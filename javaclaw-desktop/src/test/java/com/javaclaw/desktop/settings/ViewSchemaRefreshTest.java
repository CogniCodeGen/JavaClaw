package com.javaclaw.desktop.settings;

import java.util.concurrent.CompletableFuture;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.FakeGateway;
import com.javaclaw.desktop.view.ViewData;

import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.button;
import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.data;
import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.field;
import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.page;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaRefreshTest {
    @Test
    void 正在加载时收到的新失效在当前响应之后继续对账() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("初值", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            try {
                CompletableFuture<ViewData> delayed = new CompletableFuture<>();
                gateway.loadsToReturn.add(delayed);
                page.refreshAuthoritativeState();
                int before = gateway.loads;
                gateway.authoritative = data("更新后的权威值", 3);
                page.refreshAuthoritativeState();
                assertEquals(before, gateway.loads);
                delayed.complete(data("旧查询结果", 2));
                assertEquals(before + 1, gateway.loads);
                assertEquals("更新后的权威值", field(page.content()).getText());
            } finally {
                page.dispose();
            }
        });
    }

    @Test
    void 自动刷新返回前输入的草稿不能被新权威数据覆盖() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("初值", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            Stage stage = new Stage();
            stage.setScene(new Scene((Parent) page.content(), 800, 600));
            stage.show();
            try {
                page.content().applyCss();
                TextField editor = field(page.content());
                editor.requestFocus();
                editor.positionCaret(editor.getLength());
                CompletableFuture<ViewData> delayed = new CompletableFuture<>();
                gateway.loadsToReturn.add(delayed);
                page.refreshAuthoritativeState();
                assertFalse(editor.isDisabled());
                assertSame(editor, stage.getScene().getFocusOwner());
                // 向真实 Scene 的焦点控件发送键盘事件，覆盖后台刷新期间仍可持续输入的用户路径。
                stage.getScene()
                        .getFocusOwner()
                        .fireEvent(new KeyEvent(
                                KeyEvent.KEY_TYPED, "x", "x", KeyCode.UNDEFINED, false, false, false, false));
                assertEquals("初值x", editor.getText());
                assertTrue(page.dirty());
                delayed.complete(data("服务端新值", 2));
                assertSame(editor, field(page.content()));
                assertSame(editor, stage.getScene().getFocusOwner());
                assertEquals("初值x", editor.getText());
                assertTrue(page.dirty());
            } finally {
                page.dispose();
                stage.close();
            }
        });
    }

    @Test
    void 对账数据相同时保留控件焦点和文本选择() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("初始内容", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            Stage stage = new Stage();
            stage.setScene(new Scene((Parent) page.content(), 800, 600));
            stage.show();
            try {
                TextField editor = field(page.content());
                editor.requestFocus();
                editor.selectRange(1, 3);
                page.refreshAuthoritativeState();
                assertSame(editor, field(page.content()));
                assertSame(editor, stage.getScene().getFocusOwner());
                assertEquals(1, editor.getAnchor());
                assertEquals(3, editor.getCaretPosition());
            } finally {
                page.dispose();
                stage.close();
            }
        });
    }

    @Test
    void 无草稿时新权威值可以更新但保留同一字段的焦点和选区() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("初始内容", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            Stage stage = new Stage();
            stage.setScene(new Scene((Parent) page.content(), 800, 600));
            stage.show();
            try {
                TextField editor = field(page.content());
                editor.requestFocus();
                editor.selectRange(1, 3);
                gateway.authoritative = data("更新后的内容", 2);
                page.refreshAuthoritativeState();
                TextField updated = field(page.content());
                assertEquals("更新后的内容", updated.getText());
                assertSame(updated, stage.getScene().getFocusOwner());
                assertEquals(1, updated.getAnchor());
                assertEquals(3, updated.getCaretPosition());
            } finally {
                page.dispose();
                stage.close();
            }
        });
    }

    @Test
    void 显式丢弃草稿时即使服务端数据未变化也必须恢复原值() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("服务端原值", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            field(page.content()).setText("应当丢弃的草稿");
            page.discardDraft();
            assertEquals("服务端原值", field(page.content()).getText());
            assertFalse(page.dirty());
            page.dispose();
        });
    }

    @Test
    void 查询期间编辑再撤回仍按编辑代次丢弃旧自动刷新() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("初值", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            TextField editor = field(page.content());
            CompletableFuture<ViewData> delayed = new CompletableFuture<>();
            gateway.loadsToReturn.add(delayed);
            page.refreshAuthoritativeState();
            editor.setText("中间输入");
            editor.setText("初值");
            assertFalse(page.dirty());
            delayed.complete(data("过时查询返回", 2));
            assertSame(editor, field(page.content()));
            assertEquals("初值", editor.getText());
            gateway.authoritative = data("再次对账", 3);
            page.refreshAuthoritativeState();
            assertEquals("再次对账", field(page.content()).getText());
            page.dispose();
        });
    }

    @Test
    void 主动加载遮罩阻止底层键盘输入且失败与取消归还交互() {
        FxTestSupport.run(() -> {
            FakeGateway gateway = new FakeGateway(data("初值", 1));
            ViewSchemaSettingsPage page = page(gateway);
            page.activate();
            TextField editor = field(page.content());
            CompletableFuture<ViewData> delayed = new CompletableFuture<>();
            gateway.loadsToReturn.add(delayed);
            page.activate();
            assertTrue(editor.isDisabled());
            editor.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, "x", "x", KeyCode.UNDEFINED, false, false, false, false));
            assertEquals("初值", editor.getText());
            delayed.completeExceptionally(new IllegalStateException("读取失败"));
            assertFalse(editor.isDisabled());
            button(page.content(), "重试").fire();
            CompletableFuture<ViewData> cancelled = new CompletableFuture<>();
            gateway.loadsToReturn.add(cancelled);
            page.activate();
            assertTrue(editor.isDisabled());
            page.deactivate();
            assertFalse(editor.isDisabled());
            cancelled.complete(data("取消后迟到", 2));
            assertEquals("初值", editor.getText());
            page.dispose();
        });
    }
}
