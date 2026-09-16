package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.Test;

import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.ViewSchemaWireCodec;

import static com.javaclaw.desktop.settings.ViewSchemaSettingsPageTest.button;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaMultiFormDraftTest {
    @Test
    void 保存一个分区不能清空另一区草稿且同名卡片命令不能冒充表单提交() {
        FxTestSupport.run(() -> {
            var gateway = new ViewSchemaSettingsPageTest.FakeGateway(data("甲原值", "乙原值", 1));
            gateway.catalog = List.of(new ExtensionRpcContracts.ViewDocument(
                    "plan", "plan.multi", new ViewSchemaWireCodec(new CanonicalJson()).encode(schema())));
            ViewSchemaSettingsPage page = ViewSchemaSettingsPageTest.page(gateway);
            new Scene((Parent) page.content(), 880, 620);
            page.activate();
            field(page, "甲名称").setText("甲草稿");
            field(page, "乙名称").setText("乙草稿");

            button(page.content(), "保存甲").fire();
            assertEquals(0, gateway.executions);
            assertEquals(1, gateway.loads);
            assertEquals("甲草稿", field(page, "甲名称").getText());
            assertEquals("乙草稿", field(page, "乙名称").getText());
            assertTrue(page.dirty());
            button(page.content(), "继续编辑").fire();
            field(page, "乙名称").setText("乙原值");

            // 卡片恰好使用相同 operation，也不能因此获得提交当前表单草稿的资格。
            button(page.content(), "快捷动作").fire();
            assertEquals(0, gateway.executions);
            assertEquals("甲草稿", field(page, "甲名称").getText());
            button(page.content(), "继续编辑").fire();
            gateway.authoritative = data("甲已保存", "乙原值", 2);
            button(page.content(), "保存甲").fire();
            assertEquals(1, gateway.executions);
            assertEquals("甲已保存", field(page, "甲名称").getText());
            assertEquals("乙原值", field(page, "乙名称").getText());
            assertFalse(page.dirty());
            page.dispose();
        });
    }

    private static TextField field(ViewSchemaSettingsPage page, String label) {
        return page.content().lookupAll(".text-field").stream()
                .filter(TextField.class::isInstance)
                .map(TextField.class::cast)
                .filter(field -> label.equals(field.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static ViewSchema schema() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "plan.multi",
                "两个分区",
                List.of(new ViewDataSource("editor", "read", Map.of(), List.of(), 1)),
                List.of(
                        form("first", "a", "甲名称", "保存甲", "save-a"),
                        form("second", "b", "乙名称", "保存乙", "save-b"),
                        new ViewSchema.Card(
                                "shortcut", "快捷入口", "同名 operation 仍是独立卡片操作", List.of(action("快捷动作", "save-a")))));
    }

    private static ViewSchema.Form form(String id, String field, String label, String button, String command) {
        return new ViewSchema.Form(
                id,
                label,
                List.of(new ViewField(
                        "value",
                        label,
                        ViewFieldType.TEXT,
                        new ViewBinding("editor", field),
                        Optional.empty(),
                        ViewFieldValidation.required(true),
                        List.of(),
                        Optional.empty(),
                        Optional.empty())),
                action(button, command));
    }

    private static ViewAction action(String label, String command) {
        return new ViewAction(
                label, command, Map.of(), Map.of(), new ExpectedRevisionBinding.SourceRevision("editor"), false);
    }

    private static ViewData data(String first, String second, long revision) {
        return new ViewData(Map.of(
                "editor",
                new ViewData.Source(
                        List.of(), Map.of("a", first, "b", second), "", "", false, revision, 0, Optional.empty())));
    }
}
