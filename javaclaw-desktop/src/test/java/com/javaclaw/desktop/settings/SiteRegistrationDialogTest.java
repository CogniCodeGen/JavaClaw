package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.builtin.contracts.SiteRegistrationContracts;
import com.javaclaw.builtin.contracts.SiteRegistrationContracts.State;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteRegistrationDialogTest {
    @Test
    void 顶栏添加地址先保护现有草稿且完成后选中新网站() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            gateway.settings.pageSize = 2;
            SiteSettingsPage page = page(gateway);
            table(page).getSelectionModel().selectFirst();
            TextField original = field(page.content().lookup("#site-basic"), "名称");
            original.setText("原网站的未提交草稿");
            cancelConfirmation();
            button(page.content(), "添加地址").fire();
            assertTrue(registrationWindows().isEmpty());
            assertEquals("原网站的未提交草稿", original.getText());
            page.discardDraft();
            button(page.content(), "添加地址").fire();
            DialogPane dialog = registrationWindows().getFirst();
            field(dialog, "添加网站地址").setText("https://z.example.com/login");
            button(dialog, "打开隔离浏览器").fire();
            assertEquals("个人工作台", field(dialog, "登记网站名称").getText());
            button(dialog, "完成添加").fire();
            assertTrue(page.pending());
            gateway.settings.sites.add(SiteSettingsTestGateway.site("z", "个人工作台"));
            gateway.complete.complete(SiteRegistrationTestGateway.session(State.COMPLETED));
            assertTrue(registrationWindows().isEmpty());
            assertEquals("z", table(page).getSelectionModel().getSelectedItem().get("id"));
            assertFalse(page.dirty());
            page.dispose();
        });
    }

    @Test
    void 空列表仍可从添加地址进入独立登记() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            gateway.settings.sites.clear();
            SiteSettingsPage page = page(gateway);
            Button add = button(page.content(), "添加地址");
            assertFalse(add.isDisabled());
            add.fire();
            assertEquals(1, registrationWindows().size());
            page.deactivate();
            assertTrue(registrationWindows().isEmpty());
            page.dispose();
        });
    }

    @Test
    void 启动期间仍能关闭窗口且迟到浏览器被清理() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            gateway.begin = new CompletableFuture<>();
            SiteSettingsPage page = page(gateway);
            button(page.content(), "添加地址").fire();
            DialogPane dialog = registrationWindows().getFirst();
            field(dialog, "添加网站地址").setText("https://z.example.com");
            button(dialog, "打开隔离浏览器").fire();
            assertTrue(page.pending());
            Button close = (Button) dialog.lookupButton(ButtonType.CLOSE);
            assertFalse(close.isDisabled());
            close.fire();
            assertFalse(page.pending());
            gateway.begin.complete(SiteRegistrationTestGateway.session(State.ACTIVE));
            assertEquals(1, gateway.count("cancel"));
            assertTrue(registrationWindows().isEmpty());
            page.dispose();
        });
    }

    @Test
    void 工作区移除清理活动窗口且迟到结果不改变所选网站() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            SiteSettingsPage page = page(gateway);
            button(page.content(), "添加地址").fire();
            DialogPane dialog = registrationWindows().getFirst();
            field(dialog, "添加网站地址").setText("https://z.example.com");
            button(dialog, "打开隔离浏览器").fire();
            page.workspaceChanged(Optional.empty());
            assertTrue(registrationWindows().isEmpty());
            assertEquals(1, gateway.count("cancel"));
            assertFalse(page.dirty());
            page.dispose();
        });
    }

    @Test
    void 刷新保留手动名称和不保存密码选择且终态不允许继续提交() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            var dialog = dialog(gateway);
            DialogPane pane = registrationWindows().getFirst();
            field(pane, "添加网站地址").setText("https://z.example.com");
            button(pane, "打开隔离浏览器").fire();
            field(pane, "登记网站名称").setText("用户定义名称");
            ComboBox<?> candidates = descendants(pane).stream()
                    .filter(ComboBox.class::isInstance)
                    .map(ComboBox.class::cast)
                    .findFirst()
                    .orElseThrow();
            candidates.getSelectionModel().selectFirst();
            button(pane, "刷新状态").fire();
            assertEquals("用户定义名称", field(pane, "登记网站名称").getText());
            assertEquals(0, candidates.getSelectionModel().getSelectedIndex());
            button(pane, "完成添加").fire();
            var request = (SiteRegistrationContracts.CompleteRequest)
                    gateway.calls.getLast().request();
            assertTrue(request.credentialId().isEmpty());
            gateway.complete.complete(SiteRegistrationTestGateway.session(State.FAILED));
            assertTrue(button(pane, "完成添加").isDisabled());
            assertFalse(((Button) pane.lookupButton(ButtonType.CLOSE)).isDisabled());
            dialog.dispose();
        });
    }

    @Test
    void 已确认候选退休后必须重新选择不能自动改存另一份密码() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            var dialog = dialog(gateway);
            DialogPane pane = registrationWindows().getFirst();
            field(pane, "添加网站地址").setText("https://z.example.com");
            button(pane, "打开隔离浏览器").fire();
            ComboBox<?> candidates = descendants(pane).stream()
                    .filter(ComboBox.class::isInstance)
                    .map(ComboBox.class::cast)
                    .findFirst()
                    .orElseThrow();
            candidates.getSelectionModel().selectFirst();
            candidates.getSelectionModel().selectLast();
            var before = SiteRegistrationTestGateway.session(State.ACTIVE);
            var replacement = new SiteRegistrationContracts.CredentialCandidate(
                    "replacement", SiteRegistrationTestGateway.ORIGIN, "重新输入的登录表单");
            var next = new SiteRegistrationContracts.Session(
                    before.sessionId(),
                    before.state(),
                    before.access(),
                    new SiteRegistrationContracts.Page(
                            4, before.page().uri(), before.page().title(), List.of(replacement)),
                    Optional.empty());
            gateway.status = CompletableFuture.completedFuture(next);
            button(pane, "刷新状态").fire();
            assertTrue(candidates.getSelectionModel().isEmpty());
            assertTrue(button(pane, "完成添加").isDisabled());
            candidates.getSelectionModel().selectLast();
            assertFalse(button(pane, "完成添加").isDisabled());
            dialog.dispose();
        });
    }

    @Test
    void 后台状态读取保留输入和焦点仅暂停版本相关提交() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            gateway.status = new CompletableFuture<>();
            var dialog = dialog(gateway);
            DialogPane pane = registrationWindows().getFirst();
            field(pane, "添加网站地址").setText("https://z.example.com");
            button(pane, "打开隔离浏览器").fire();
            TextField name = field(pane, "登记网站名称");
            TextField origin = field(pane, "本次登记额外允许的 HTTPS 来源");
            name.setText("正在编辑名称");
            origin.setText("https://login.example.com");
            name.requestFocus();
            name.positionCaret(2);
            assertSame(name, pane.getScene().getFocusOwner());
            button(pane, "刷新状态").fire();
            assertFalse(name.isDisabled());
            assertFalse(origin.isDisabled());
            assertTrue(descendants(pane).stream()
                    .filter(ComboBox.class::isInstance)
                    .noneMatch(Node::isDisabled));
            assertSame(name, pane.getScene().getFocusOwner());
            assertEquals(2, name.getCaretPosition());
            assertTrue(button(pane, "允许此来源").isDisabled());
            assertTrue(button(pane, "完成添加").isDisabled());
            name.appendText("继续输入");
            gateway.status.complete(SiteRegistrationTestGateway.session(State.ACTIVE));
            assertEquals("正在编辑名称继续输入", name.getText());
            assertEquals("https://login.example.com", origin.getText());
            assertSame(name, pane.getScene().getFocusOwner());
            assertFalse(button(pane, "允许此来源").isDisabled());
            assertFalse(button(pane, "完成添加").isDisabled());
            dialog.dispose();
        });
    }

    @Test
    void 窄窗口使用真实滚动容器且仅展示脱敏候选() {
        FxTestSupport.run(() -> {
            var gateway = new SiteRegistrationTestGateway();
            var dialog = dialog(gateway);
            DialogPane pane = registrationWindows().getFirst();
            assertTrue(pane.getContent() instanceof ScrollPane);
            field(pane, "添加网站地址").setText("https://z.example.com");
            button(pane, "打开隔离浏览器").fire();
            pane.getScene().getWindow().setWidth(480);
            pane.getScene().getWindow().setHeight(400);
            pane.applyCss();
            pane.layout();
            ScrollPane scroll = (ScrollPane) pane.getContent();
            assertTrue(scroll.isFitToWidth());
            assertTrue(scroll.getContent().getBoundsInLocal().getHeight()
                    > scroll.getViewportBounds().getHeight());
            assertTrue(descendants(pane).stream().noneMatch(javafx.scene.control.PasswordField.class::isInstance));
            SiteRegistrationWindowEvidence.capture(pane, "registration-active-narrow.png");
            scroll.setVvalue(0.65);
            SiteRegistrationWindowEvidence.capture(pane, "registration-active-controls-narrow.png");
            scroll.setVvalue(0);
            gateway.status = CompletableFuture.completedFuture(SiteRegistrationTestGateway.session(State.FAILED));
            button(pane, "刷新状态").fire();
            SiteRegistrationWindowEvidence.capture(pane, "registration-failed-narrow.png");
            scroll.setVvalue(0.65);
            SiteRegistrationWindowEvidence.capture(pane, "registration-failed-status-narrow.png");
            scroll.setVvalue(1);
            SiteRegistrationWindowEvidence.capture(pane, "registration-failed-origins-narrow.png");
            dialog.dispose();
        });
    }

    static SiteRegistrationDialog dialog(SiteRegistrationTestGateway gateway) {
        var dialog = new SiteRegistrationDialog(
                gateway, DesktopTestFixtures.workspace().id(), ignored -> {}, () -> {});
        dialog.show(null);
        return dialog;
    }

    static List<DialogPane> registrationWindows() {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .map(window -> window.getScene().getRoot())
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .filter(pane -> "在隔离浏览器中登录并添加网站".equals(pane.getHeaderText()))
                .toList();
    }

    static Button button(Node root, String label) {
        return descendants(root).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> label.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    static TextField field(Node root, String accessibleText) {
        return descendants(root).stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> accessibleText.equals(field.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static SiteSettingsPage page(SiteRegistrationTestGateway gateway) {
        var page = new SiteSettingsPage(new TestCoreSettingsGateway(), gateway);
        ScrollPane scroll = new ScrollPane(page.content());
        scroll.setFitToWidth(true);
        new Scene(scroll, 880, 620);
        page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
        page.activate();
        scroll.applyCss();
        scroll.layout();
        return page;
    }

    private static void cancelConfirmation() {
        Platform.runLater(() -> Window.getWindows().stream()
                .filter(Window::isShowing)
                .map(window -> window.getScene().getRoot())
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .filter(pane -> pane.getButtonTypes().contains(ButtonType.CANCEL))
                .findFirst()
                .ifPresent(pane -> ((Button) pane.lookupButton(ButtonType.CANCEL)).fire()));
    }

    @SuppressWarnings("unchecked")
    private static TableView<Map<String, Object>> table(SiteSettingsPage page) {
        return (TableView<Map<String, Object>>) page.content().lookup("#site-list");
    }

    private static List<Node> descendants(Node root) {
        List<Node> result = new ArrayList<>();
        result.add(root);
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> result.addAll(descendants(child)));
        }
        if (root instanceof ScrollPane scroll && scroll.getContent() != null && !result.contains(scroll.getContent())) {
            result.addAll(descendants(scroll.getContent()));
        }
        return result;
    }
}
