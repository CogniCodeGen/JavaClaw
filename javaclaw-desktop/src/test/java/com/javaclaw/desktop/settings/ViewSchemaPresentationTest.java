package com.javaclaw.desktop.settings;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewRenderLayout;
import com.javaclaw.extension.spi.ViewSchema;

import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.button;
import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.data;
import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaPresentationTest {
    @Test
    void 外部秘密草稿阻止自动刷新和其他表单提交且丢弃后恢复权威数据() {
        FxTestSupport.run(() -> {
            var gateway = new ViewSchemaSettingsPageTest.FakeGateway(data("原值", 1));
            ViewSchemaSettingsPage page = ViewSchemaSettingsPageTest.page(gateway);
            AtomicBoolean externalDirty = new AtomicBoolean();
            AtomicInteger stateChanges = new AtomicInteger();
            AtomicInteger writes = new AtomicInteger();
            page.configurePresentation(
                    new FlatLayout(), externalDirty::get, () -> false, () -> externalDirty.set(false));
            page.onStateChanged(stateChanges::incrementAndGet);
            page.onCommandSucceeded((invocation, result) -> writes.incrementAndGet());
            new Scene((Parent) page.content(), 880, 620);
            page.activate();
            field(page.content()).setText("表单草稿");
            externalDirty.set(true);
            gateway.authoritative = data("服务端新值", 2);
            page.refreshAuthoritativeState();
            assertEquals(1, gateway.loads);
            button(page.content(), "继续编辑").fire();
            button(page.content(), "保存").fire();
            assertEquals(0, writes.get());
            assertEquals("表单草稿", field(page.content()).getText());
            assertTrue(page.dirty());
            assertTrue(externalDirty.get());
            page.discardDraft();
            assertEquals(2, gateway.loads);
            assertEquals("服务端新值", field(page.content()).getText());
            assertFalse(page.dirty());
            assertFalse(externalDirty.get());
            assertTrue(stateChanges.get() > 0);
            page.dispose();
        });
    }

    @Test
    void 外部写入阻止上下文切换但不改变本页公开本地Pending语义() {
        FxTestSupport.run(() -> {
            var gateway = new ViewSchemaSettingsPageTest.FakeGateway(data("原值", 1));
            ViewSchemaSettingsPage page = ViewSchemaSettingsPageTest.page(gateway);
            AtomicBoolean externalPending = new AtomicBoolean();
            page.configurePresentation(new FlatLayout(), () -> false, externalPending::get, () -> {});
            page.activate();
            externalPending.set(true);
            assertFalse(page.pending(), "本页 pending 保持局部语义，平台聚合不能形成循环引用");
            assertFalse(page.confirmContextChange());
            gateway.authoritative = data("外部写入后的权威值", 2);
            page.refreshAuthoritativeState();
            assertEquals(1, gateway.loads);
            externalPending.set(false);
            page.refreshAuthoritativeState();
            assertEquals(2, gateway.loads);
            assertTrue(page.confirmContextChange());
            assertEquals("外部写入后的权威值", field(page.content()).getText());
            page.dispose();
        });
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
