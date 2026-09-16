package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javafx.scene.Node;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.desktop.DesktopTestFixtures;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewRenderLayout;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.ExtensionRpcContracts;

import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.button;
import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.data;
import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaSubscriptionLifecycleTest {
    @Test
    void 固定页面停用清理订阅重开重新校验目录且拒绝旧通知() {
        FxTestSupport.run(() -> {
            var gateway = new ViewSchemaSettingsPageTest.FakeGateway(data("原值", 1));
            ViewSchemaSettingsPage page = fixedPage(gateway);
            assertTrue(gateway.subscriptions.isEmpty());
            page.activate();
            page.activate();
            assertEquals(1, gateway.subscriptions.size());
            var oldListener = gateway.subscriptions.getFirst();
            page.deactivate();
            page.deactivate();
            assertEquals(1, gateway.subscriptionCloses);
            gateway.authoritative = data("停用期间更新", 2);
            oldListener.accept(event(100));
            assertEquals(1, gateway.loads);
            page.activate();
            assertEquals(2, gateway.subscriptions.size());
            assertEquals(2, gateway.catalogs);
            assertEquals(2, gateway.loads);
            assertEquals("停用期间更新", field(page.content()).getText());
            oldListener.accept(event(101));
            assertEquals(2, gateway.loads);
            gateway.subscriptions.getLast().accept(event(2));
            assertEquals(3, gateway.loads);
            page.deactivate();
            gateway.catalog = List.of();
            page.activate();
            assertEquals(3, gateway.catalogs);
            assertEquals(3, gateway.loads, "重开时已禁用的扩展不得继续查询旧页面");
            page.dispose();
            assertEquals(3, gateway.subscriptionCloses);
        });
    }

    @Test
    void 固定页面重开不覆盖草稿并在放弃后读取遗漏更新() {
        FxTestSupport.run(() -> {
            var gateway = new ViewSchemaSettingsPageTest.FakeGateway(data("原值", 1));
            ViewSchemaSettingsPage page = fixedPage(gateway);
            page.activate();
            field(page.content()).setText("保留草稿");
            page.deactivate();
            gateway.authoritative = data("新权威值", 2);
            page.activate();
            assertEquals(1, gateway.catalogs);
            assertEquals(1, gateway.loads);
            assertTrue(page.dirty());
            assertEquals("保留草稿", field(page.content()).getText());
            page.discardDraft();
            assertEquals(2, gateway.catalogs);
            assertEquals("新权威值", field(page.content()).getText());
            assertFalse(page.dirty());
            page.dispose();
        });
    }

    @Test
    void 固定页面隐藏时已发送命令继续接收回执且重开不重放() {
        FxTestSupport.run(() -> {
            var gateway = new ViewSchemaSettingsPageTest.FakeGateway(data("原值", 1));
            gateway.command = new CompletableFuture<>();
            ViewSchemaSettingsPage page = fixedPage(gateway);
            page.activate();
            field(page.content()).setText("待提交");
            button(page.content(), "保存").fire();
            page.deactivate();
            assertTrue(page.pending());
            assertEquals(1, gateway.subscriptionCloses);
            gateway.authoritative = data("已提交", 2);
            gateway.command.complete(new ExtensionRpcContracts.CallResult(new CanonicalPayload("{}"), 2));
            assertFalse(page.pending());
            assertEquals(1, gateway.loads);
            page.activate();
            assertEquals(2, gateway.loads);
            assertEquals(1, gateway.executions);
            assertEquals("已提交", field(page.content()).getText());
            page.dispose();
        });
    }

    @Test
    void 默认页面仍接收停用事件且激活不重复订阅() {
        FxTestSupport.run(() -> {
            var gateway = new ViewSchemaSettingsPageTest.FakeGateway(data("原值", 1));
            ViewSchemaSettingsPage page = ViewSchemaSettingsPageTest.page(gateway);
            page.activate();
            page.deactivate();
            assertEquals(0, gateway.subscriptionCloses);
            gateway.authoritative = data("停用期间更新", 2);
            gateway.subscriptions.getFirst().accept(event(2));
            page.activate();
            assertEquals(1, gateway.subscriptions.size());
            assertEquals(1, gateway.catalogs);
            assertEquals(2, gateway.loads);
            page.dispose();
            assertEquals(1, gateway.subscriptionCloses);
        });
    }

    private static ViewSchemaSettingsPage fixedPage(ViewSchemaSettingsPageTest.FakeGateway gateway) {
        ViewSchemaSettingsPage page = new ViewSchemaSettingsPage("plan", "计划", "测试页面", "plan.editor", gateway);
        page.configurePresentation(new FlatLayout(), () -> false, () -> false, () -> {});
        page.workspaceChanged(Optional.of(DesktopTestFixtures.workspace()));
        return page;
    }

    private static ExtensionRpcContracts.ExtensionEvent event(long revision) {
        return new ExtensionRpcContracts.ExtensionEvent(
                DesktopTestFixtures.workspace().id(), "plan", "document", "primary", "put", revision);
    }

    private static final class FlatLayout implements ViewRenderLayout {
        private final VBox root = new VBox();

        @Override
        public Node node() {
            return root;
        }

        @Override
        public void apply(ViewSchema schema, Map<String, Node> nodes, ViewData data) {
            root.getChildren().setAll(nodes.values());
        }

        @Override
        public void close() {
            root.getChildren().clear();
        }
    }
}
