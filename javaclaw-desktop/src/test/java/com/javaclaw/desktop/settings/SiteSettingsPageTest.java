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
import javafx.scene.control.DialogPane;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.NativeUiEvidence;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteSettingsPageTest {
    @Test
    void 上方列表统一驱动网站详情与按需账号查询() {
        FxTestSupport.run(() -> {
            SiteSettingsTestGateway gateway = new SiteSettingsTestGateway();
            SiteSettingsPage page = attach(gateway, 880, 620);
            assertTrue(table(page).getSelectionModel().isEmpty());
            assertEquals(3, table(page).getColumns().size());
            assertTrue(gateway.accountQueries.isEmpty());
            assertFalse(page.content().lookup("#site-detail-sections").isVisible());
            table(page).getSelectionModel().select(0);
            assertEquals("文档网站", basicName(page).getText());
            assertTrue(table(page).getMaxHeight() <= 240);
            section(page, "site-accounts").setExpanded(true);
            assertEquals(List.of("a"), gateway.accountQueries);
            table(page).getSelectionModel().select(1);
            assertEquals("团队网站", basicName(page).getText());
            section(page, "site-accounts").setExpanded(true);
            assertEquals(List.of("a", "b"), gateway.accountQueries);
            page.dispose();
        });
    }

    @Test
    void 取消带草稿的网站切换保留选中行和命令身份() {
        FxTestSupport.run(() -> {
            SiteSettingsTestGateway gateway = new SiteSettingsTestGateway();
            SiteSettingsPage page = attach(gateway, 880, 620);
            table(page).getSelectionModel().select(0);
            basicName(page).setText("尚未保存");
            completeDialog(ButtonType.CANCEL);
            table(page).getSelectionModel().select(1);
            assertEquals("a", table(page).getSelectionModel().getSelectedItem().get("id"));
            assertEquals("尚未保存", basicName(page).getText());
            button(section(page, "site-basic").getContent(), "保存网站").fire();
            assertEquals("a", gateway.commands.getFirst().arguments().get("id"));
            assertEquals("尚未保存", basicName(page).getText());
            page.dispose();
        });
    }

    @Test
    void 新建后跨页定位且删除当前网站后清空详情() {
        FxTestSupport.run(() -> {
            SiteSettingsTestGateway gateway = new SiteSettingsTestGateway();
            gateway.pageSize = 2;
            SiteSettingsPage page = attach(gateway, 1040, 720);
            button(page.content(), "新建网站").fire();
            field(page.content(), "网站标识").setText("z");
            field(page.content(), "名称").setText("新网站");
            button(page.content(), "创建网站").fire();
            assertEquals("z", table(page).getSelectionModel().getSelectedItem().get("id"));
            assertEquals("新网站", basicName(page).getText());
            section(page, "site-advanced").setExpanded(true);
            completeDialog(ButtonType.OK);
            button(section(page, "site-advanced").getContent(), "删除网站").fire();
            assertTrue(table(page).getSelectionModel().isEmpty());
            assertFalse(page.content().lookup("#site-detail-sections").isVisible());
            page.dispose();
        });
    }

    @Test
    void 保存失败保留草稿并锁定期间的上下文切换() {
        FxTestSupport.run(() -> {
            SiteSettingsTestGateway gateway = new SiteSettingsTestGateway();
            gateway.command = new CompletableFuture<>();
            SiteSettingsPage page = attach(gateway, 880, 620);
            table(page).getSelectionModel().select(0);
            TextField name = basicName(page);
            name.setText("保存失败的草稿");
            button(section(page, "site-basic").getContent(), "保存网站").fire();
            assertTrue(page.pending());
            assertTrue(button(page.content(), "新建网站").isDisabled());
            table(page).getSelectionModel().select(1);
            assertEquals("a", table(page).getSelectionModel().getSelectedItem().get("id"));
            gateway.command.completeExceptionally(new IllegalStateException("连接已断开"));
            assertFalse(page.pending());
            assertTrue(page.dirty());
            assertEquals("保存失败的草稿", name.getText());
            page.dispose();
        });
    }

    @Test
    void 直接收起账号分区也必须确认丢弃秘密草稿() {
        FxTestSupport.run(() -> {
            SiteSettingsPage page = attach(new SiteSettingsTestGateway(), 880, 620);
            table(page).getSelectionModel().select(0);
            TitledPane accounts = section(page, "site-accounts");
            accounts.setExpanded(true);
            PasswordField password = descendants(accounts.getContent()).stream()
                    .filter(PasswordField.class::isInstance)
                    .map(PasswordField.class::cast)
                    .findFirst()
                    .orElseThrow();
            password.setText("尚未提交的秘密");
            completeDialog(ButtonType.CANCEL);
            accounts.setExpanded(false);
            assertTrue(accounts.isExpanded());
            assertEquals("尚未提交的秘密", password.getText());
            completeDialog(ButtonType.OK);
            accounts.setExpanded(false);
            assertFalse(accounts.isExpanded());
            assertEquals("", password.getText());
            page.dispose();
        });
    }

    @Test
    void 关闭后迟到加载不能重建旧网站配置() {
        FxTestSupport.run(() -> {
            SiteSettingsTestGateway gateway = new SiteSettingsTestGateway();
            SiteSettingsPage page = attach(gateway, 880, 620);
            CompletableFuture<ViewData> delayed = new CompletableFuture<>();
            gateway.nextLoad = delayed;
            table(page).getSelectionModel().select(0);
            page.dispose();
            delayed.complete(gateway.data(new ViewLoadRequest(Map.of(), Map.of("documents", "a"), Map.of())));
            assertTrue(gateway.accountQueries.isEmpty());
            assertFalse(page.dirty());
        });
    }

    @Test
    void 网站页面在两种窗口尺寸中保留列表和折叠配置布局() {
        FxTestSupport.run(() -> {
            for (int width : List.of(880, 1040)) {
                int height = width == 880 ? 620 : 720;
                SiteSettingsPage page = attach(new SiteSettingsTestGateway(), width, height);
                NativeUiEvidence.capture(page.content().getScene().getRoot(), "site-empty-selection-" + width + ".png");
                table(page).getSelectionModel().select(0);
                NativeUiEvidence.capture(page.content().getScene().getRoot(), "site-basic-" + width + ".png");
                section(page, "site-accounts").setExpanded(true);
                assertEquals(List.of("site-accounts"), expandedSections(page));
                NativeUiEvidence.capture(page.content().getScene().getRoot(), "site-accounts-" + width + ".png");
                assertEquals(List.of("site-accounts"), expandedSections(page));
                page.content().getScene().getRoot().layout();
                assertTrue(
                        section(page, "site-basic").getHeight() < 80,
                        "收起基本信息后必须释放实际布局高度：" + section(page, "site-basic").getHeight());
                page.dispose();
                captureEmptyAndError(width, height);
            }
        });
    }

    private static void captureEmptyAndError(int width, int height) {
        SiteSettingsTestGateway empty = new SiteSettingsTestGateway();
        empty.sites.clear();
        SiteSettingsPage emptyPage = attach(empty, width, height);
        assertTrue(table(emptyPage).getItems().isEmpty());
        NativeUiEvidence.capture(emptyPage.content().getScene().getRoot(), "site-empty-list-" + width + ".png");
        emptyPage.dispose();
        SiteSettingsTestGateway failed = new SiteSettingsTestGateway();
        failed.nextLoad = CompletableFuture.failedFuture(new IllegalStateException("连接暂时不可用"));
        SiteSettingsPage errorPage = attach(failed, width, height);
        assertFalse(button(errorPage.content(), "重试").isDisabled());
        NativeUiEvidence.capture(errorPage.content().getScene().getRoot(), "site-load-error-" + width + ".png");
        errorPage.dispose();
    }

    private static SiteSettingsPage attach(SiteSettingsTestGateway gateway, int width, int height) {
        SiteSettingsPage page = new SiteSettingsPage(new TestCoreSettingsGateway(), gateway);
        ScrollPane scroll = new ScrollPane(page.content());
        scroll.setFitToWidth(true);
        new Scene(scroll, width, height);
        page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
        page.activate();
        scroll.applyCss();
        scroll.layout();
        return page;
    }

    @SuppressWarnings("unchecked")
    private static TableView<Map<String, Object>> table(SiteSettingsPage page) {
        return (TableView<Map<String, Object>>) page.content().lookup("#site-list");
    }

    private static TitledPane section(SiteSettingsPage page, String id) {
        return descendants(page.content()).stream()
                .filter(TitledPane.class::isInstance)
                .map(TitledPane.class::cast)
                .filter(pane -> id.equals(pane.getId()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> expandedSections(SiteSettingsPage page) {
        return List.of("site-basic", "site-accounts", "site-advanced").stream()
                .filter(id -> section(page, id).isExpanded())
                .toList();
    }

    private static TextField basicName(SiteSettingsPage page) {
        return field(section(page, "site-basic").getContent(), "名称");
    }

    private static TextField field(Node node, String label) {
        return descendants(node).stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> label.equals(field.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static Button button(Node node, String text) {
        return descendants(node).stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static List<Node> descendants(Node node) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(node);
        if (node instanceof TitledPane pane && pane.getContent() != null) {
            nodes.addAll(descendants(pane.getContent()));
        } else if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> nodes.addAll(descendants(child)));
        }
        return nodes;
    }

    private static void completeDialog(ButtonType result) {
        Platform.runLater(() -> Window.getWindows().stream()
                .map(Window::getScene)
                .filter(java.util.Objects::nonNull)
                .map(Scene::getRoot)
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .filter(dialog -> dialog.getButtonTypes().contains(result))
                .findFirst()
                .map(dialog -> dialog.lookupButton(result))
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .ifPresent(Button::fire));
    }
}
