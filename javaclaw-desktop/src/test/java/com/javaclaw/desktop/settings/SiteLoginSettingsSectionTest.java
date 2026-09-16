package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteLoginSettingsSectionTest {
    @Test
    void 双层展开才加载且开始命令绑定父网站和权威版本() {
        FxTestSupport.run(() -> {
            SiteLoginSettingsTestGateway gateway = new SiteLoginSettingsTestGateway();
            AtomicInteger changed = new AtomicInteger();
            SiteLoginSettingsSection section = new SiteLoginSettingsSection(gateway, changed::incrementAndGet);
            Parent root = attach(section);
            section.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
            section.setSite(Optional.of(SiteSettingsTestGateway.site("a", "网站A")));
            section.activate();
            assertEquals(0, gateway.lists);
            pane(section).setExpanded(true);
            assertEquals("a", gateway.queries.getFirst().arguments().get("siteId"));
            assertEquals(1, nodes(root, TableView.class).size());
            pane(section).setExpanded(false);
            pane(section).setExpanded(true);
            assertEquals(1, gateway.queries.size());
            button(root, "开始隔离登录").fire();
            assertTrue(section.pending());
            assertEquals("a", gateway.commands.getFirst().arguments().get("siteId"));
            assertEquals(1L, ((Number) gateway.commands.getFirst().arguments().get("expectedRevision")).longValue());
            pane(section).setExpanded(false);
            assertTrue(pane(section).isExpanded());
            gateway.command.complete(gateway.response("a"));
            assertFalse(section.pending());
            assertEquals(1, changed.get());
            assertEquals(2, gateway.queries.size());
            section.dispose();
        });
    }

    @Test
    void 草稿阻止登录提交且服务失败保留局部重试入口() {
        FxTestSupport.run(() -> {
            SiteLoginSettingsTestGateway gateway = new SiteLoginSettingsTestGateway();
            SiteLoginSettingsSection section = opened(gateway);
            Parent root = (Parent) section.content().getParent();
            AtomicBoolean dirty = new AtomicBoolean(true);
            section.setContextGuard(dirty::get, () -> false);
            assertTrue(button(root, "开始隔离登录").isDisabled());
            dirty.set(false);
            section.refreshContext();
            button(root, "开始隔离登录").fire();
            gateway.command.completeExceptionally(new IllegalStateException("版本冲突"));
            assertFalse(section.pending());
            assertTrue(labels(root).contains("版本冲突"));
            assertFalse(button(root, "开始隔离登录").isDisabled());
            gateway.failSynchronously = true;
            button(root, "开始隔离登录").fire();
            assertFalse(section.pending());
            assertTrue(labels(root).contains("服务暂时不可用"));
            section.warnUnsavedChanges();
            assertTrue(labels(root).contains("请先保存"));
            section.discardDraft();
            assertFalse(section.dirty());
            section.dispose();
        });
    }

    @Test
    void 旧查询和旧写回执不能回填另一网站或Workspace() {
        FxTestSupport.run(() -> {
            SiteLoginSettingsTestGateway gateway = new SiteLoginSettingsTestGateway();
            gateway.query = new CompletableFuture<>();
            SiteLoginSettingsSection section = opened(gateway);
            CompletableFuture<com.javaclaw.protocol.ExtensionRpcContracts.CallResult> old = gateway.query;
            gateway.query = null;
            section.setSite(Optional.of(SiteSettingsTestGateway.site("b", "网站B")));
            old.complete(gateway.response("a"));
            assertEquals("b", gateway.queries.getLast().arguments().get("siteId"));
            Parent root = (Parent) section.content().getParent();
            button(root, "开始隔离登录").fire();
            section.workspaceChanged(Optional.empty());
            gateway.command.complete(gateway.response("b"));
            assertFalse(section.pending());
            assertTrue(nodes(root, TableView.class).isEmpty());
            assertTrue(labels(root).contains("请选择网站"));
            section.dispose();
        });
    }

    @Test
    void 登录能力不可用或查询失败只影响局部且可刷新恢复() {
        FxTestSupport.run(() -> {
            SiteLoginSettingsTestGateway gateway = new SiteLoginSettingsTestGateway();
            gateway.enabled = false;
            SiteLoginSettingsSection section = opened(gateway);
            Parent root = (Parent) section.content().getParent();
            assertTrue(labels(root).contains("网页登录配置当前不可用"));
            assertEquals(0, gateway.queries.size());
            gateway.enabled = true;
            gateway.available = false;
            button(root, "刷新登录会话").fire();
            assertTrue(labels(root).contains("当前平台尚未提供"));
            section.deactivate();
            section.invalidateCache();
            int before = gateway.queries.size();
            assertEquals(before, gateway.queries.size());
            section.activate();
            assertEquals(before + 1, gateway.queries.size());
            gateway.query = CompletableFuture.failedFuture(new IllegalStateException("登录查询失败"));
            button(root, "刷新登录会话").fire();
            assertTrue(labels(root).contains("登录查询失败"));
            section.dispose();
        });
    }

    @Test
    void 保存会话沿用危险确认而取消操作使用所选会话() {
        FxTestSupport.run(() -> {
            SiteLoginSettingsTestGateway gateway = new SiteLoginSettingsTestGateway();
            SiteLoginSettingsSection section = opened(gateway);
            Parent root = (Parent) section.content().getParent();
            nodes(root, TableView.class).getFirst().getSelectionModel().select(0);
            Platform.runLater(() -> dialogButton(ButtonType.CANCEL).fire());
            button(root, "保存登录").fire();
            assertTrue(gateway.commands.isEmpty());
            Platform.runLater(() -> dialogButton(ButtonType.OK).fire());
            button(root, "保存登录").fire();
            assertEquals("login.save", gateway.commands.getFirst().operation());
            assertTrue(gateway.commands.getFirst().dangerous());
            gateway.command.completeExceptionally(new IllegalStateException("保存失败"));
            nodes(root, TableView.class).getFirst().getSelectionModel().select(0);
            gateway.command = new CompletableFuture<>();
            button(root, "取消").fire();
            assertEquals("login.cancel", gateway.commands.getLast().operation());
            assertEquals(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    gateway.commands.getLast().arguments().get("sessionId"));
            section.dispose();
        });
    }

    private static SiteLoginSettingsSection opened(SiteLoginSettingsTestGateway gateway) {
        SiteLoginSettingsSection section = new SiteLoginSettingsSection(gateway, () -> {});
        attach(section);
        section.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
        section.setSite(Optional.of(SiteSettingsTestGateway.site("a", "网站A")));
        section.activate();
        pane(section).setExpanded(true);
        return section;
    }

    private static Parent attach(SiteLoginSettingsSection section) {
        VBox root = new VBox(section.content());
        new Scene(root, 880, 620);
        root.applyCss();
        root.layout();
        return root;
    }

    private static TitledPane pane(SiteLoginSettingsSection section) {
        return (TitledPane) section.content();
    }

    private static Button button(Parent root, String text) {
        return nodes(root, Button.class).stream()
                .filter(button -> button.getText().equals(text))
                .findFirst()
                .orElseThrow();
    }

    private static Button dialogButton(ButtonType type) {
        DialogPane dialog = Window.getWindows().stream()
                .filter(Window::isShowing)
                .map(window -> window.getScene().getRoot())
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .findFirst()
                .orElseThrow();
        return (Button) dialog.lookupButton(type);
    }

    private static String labels(Parent root) {
        return nodes(root, Label.class).stream().map(Label::getText).reduce("", (left, right) -> left + right);
    }

    private static <T> List<T> nodes(Parent root, Class<T> type) {
        java.util.ArrayList<T> found = new java.util.ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T> void collect(Node node, Class<T> type, List<T> found) {
        if (type.isInstance(node)) {
            found.add(type.cast(node));
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, type, found));
        }
    }
}
