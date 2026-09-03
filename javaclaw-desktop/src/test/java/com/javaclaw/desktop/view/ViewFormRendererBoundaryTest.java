package com.javaclaw.desktop.view;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCondition;
import com.javaclaw.extension.spi.ViewConditionOperator;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewOptionFilter;
import com.javaclaw.extension.spi.ViewOptionSource;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewFormRendererBoundaryTest {
    @Test
    void 按字段顺序报告必填长度和数值边界且最终提交规范值() {
        FxTestSupport.run(() -> {
            RecordingInteractions interactions = new RecordingInteractions();
            VBox page = render(validationSchema(), validationData(), interactions);
            Button submit = button(page, "保存");
            ComboBox<ViewOption> kind = combo(page, "类型");
            TextField title = text(page, "标题");
            TextField amount = text(page, "数量");

            title.setText("edit");
            submit.fire();
            assertFeedback(page, "类型不能为空");

            kind.setValue(kind.getItems().getFirst());
            title.setText(" ");
            submit.fire();
            assertFeedback(page, "标题不能为空");

            title.setText("a");
            submit.fire();
            assertFeedback(page, "标题长度不足");

            title.setText("abcde");
            submit.fire();
            assertFeedback(page, "标题长度超出限制");

            title.setText("okay");
            amount.setText("not-a-number");
            submit.fire();
            assertFeedback(page, "数量必须是数值");

            amount.setText("0");
            submit.fire();
            assertFeedback(page, "数量小于允许的最小值");

            amount.setText("11");
            submit.fire();
            assertFeedback(page, "数量大于允许的最大值");

            amount.setText("6.0");
            submit.fire();
            assertEquals(1, interactions.commands.size());
            assertEquals(
                    new BigDecimal("6"),
                    interactions.commands.getFirst().arguments().get("amount"));
            assertEquals("safe", interactions.commands.getFirst().arguments().get("kind"));
        });
    }

    @Test
    void 条件显示同时支持表单状态外部状态和非等于运算() {
        FxTestSupport.run(() -> {
            VBox page = render(conditionSchema(), conditionData(), new RecordingInteractions());
            CheckBox enabled = checkBox(page, "启用高级项");
            TextField whenDisabled = text(page, "停用时说明");
            TextField externalVisible = text(page, "平台已就绪");
            TextField externalHidden = text(page, "平台未就绪");

            assertTrue(whenDisabled.isVisible());
            assertTrue(externalVisible.isVisible());
            assertFalse(externalHidden.isVisible());

            enabled.setSelected(true);
            assertFalse(whenDisabled.isVisible());
        });
    }

    @Test
    void 动态选项响应文本和布尔依赖且不会保留失效选择() {
        FxTestSupport.run(() -> {
            VBox page = render(dynamicSchema(), dynamicData(), new RecordingInteractions());
            TextField category = text(page, "工具类别");
            CheckBox destructive = checkBox(page, "是否写入");
            ComboBox<ViewOption> byCategory = combo(page, "同类别工具");
            ComboBox<ViewOption> byRisk = combo(page, "同风险工具");

            assertEquals("read", byCategory.getValue().value());
            assertEquals("inspect", byRisk.getValue().value());
            assertEquals("", byCategory.getConverter().toString(null));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> byCategory.getConverter().fromString("free"));

            category.setText("write");
            assertEquals(
                    List.of("write"),
                    byCategory.getItems().stream().map(ViewOption::value).toList());
            assertEquals(null, byCategory.getValue());

            destructive.setSelected(true);
            assertEquals(
                    List.of("write"),
                    byRisk.getItems().stream().map(ViewOption::value).toList());
            assertEquals(null, byRisk.getValue());

            category.setText("missing");
            assertTrue(byCategory.getItems().isEmpty());
            assertTrue(byCategory.isDisabled());
        });
    }

    private static ViewSchema validationSchema() {
        ViewField optionalNumber = field(
                "optionalNumber",
                "可选数量",
                ViewFieldType.NUMBER,
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewField kind = field(
                "kind",
                "类型",
                ViewFieldType.CHOICE,
                ViewFieldValidation.required(true),
                List.of(new ViewOption("safe", "安全")),
                Optional.empty(),
                Optional.empty());
        ViewField title = field(
                "title",
                "标题",
                ViewFieldType.TEXT,
                new ViewFieldValidation(
                        true, Optional.of(2), Optional.of(4), Optional.empty(), Optional.empty(), Optional.empty()),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewField amount = field(
                "amount",
                "数量",
                ViewFieldType.NUMBER,
                new ViewFieldValidation(
                        true,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(BigDecimal.ONE),
                        Optional.of(BigDecimal.TEN),
                        Optional.empty()),
                List.of(),
                Optional.empty(),
                Optional.empty());
        return formSchema("validation", List.of(optionalNumber, kind, title, amount), List.of(source("editor")));
    }

    private static ViewData validationData() {
        return new ViewData(Map.of("editor", dataSource(Map.of("title", "ok", "amount", 5), List.of(), 4)));
    }

    private static ViewSchema conditionSchema() {
        ViewBinding enabled = new ViewBinding("editor", "enabled");
        ViewField toggle = field(
                "enabled",
                "启用高级项",
                ViewFieldType.BOOLEAN,
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewField whenDisabled = field(
                "whenDisabled",
                "停用时说明",
                ViewFieldType.TEXT,
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.of(new ViewCondition(enabled, ViewConditionOperator.NOT_EQUALS, "true")));
        ViewField externalVisible = field(
                "externalVisible",
                "平台已就绪",
                ViewFieldType.TEXT,
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.of(new ViewCondition(new ViewBinding("flags", "ready"), ViewConditionOperator.EQUALS, "yes")));
        ViewField externalHidden = field(
                "externalHidden",
                "平台未就绪",
                ViewFieldType.TEXT,
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.of(new ViewCondition(new ViewBinding("flags", "ready"), ViewConditionOperator.EQUALS, "no")));
        return formSchema(
                "conditions",
                List.of(toggle, whenDisabled, externalVisible, externalHidden),
                List.of(source("editor"), source("flags")));
    }

    private static ViewData conditionData() {
        return new ViewData(Map.of(
                "editor", dataSource(Map.of("enabled", false), List.of(), 2),
                "flags", dataSource(Map.of("ready", "yes"), List.of(), 1)));
    }

    private static ViewSchema dynamicSchema() {
        ViewField category = field(
                "category",
                "工具类别",
                ViewFieldType.TEXT,
                ViewFieldValidation.required(true),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewField destructive = field(
                "destructive",
                "是否写入",
                ViewFieldType.BOOLEAN,
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty(),
                Optional.empty());
        ViewField byCategory = field(
                "byCategory",
                "同类别工具",
                ViewFieldType.CHOICE,
                ViewFieldValidation.required(true),
                List.of(),
                Optional.of(optionSource("category", "category")),
                Optional.empty());
        ViewField byRisk = field(
                "byRisk",
                "同风险工具",
                ViewFieldType.CHOICE,
                ViewFieldValidation.required(true),
                List.of(),
                Optional.of(optionSource("destructive", "destructive")),
                Optional.empty());
        return formSchema(
                "dynamic",
                List.of(category, destructive, byCategory, byRisk),
                List.of(source("editor"), source("options")));
    }

    private static ViewOptionSource optionSource(String sourceField, String inputField) {
        return new ViewOptionSource(
                "options", "value", "label", Optional.of(new ViewOptionFilter(sourceField, inputField)));
    }

    private static ViewData dynamicData() {
        List<Map<String, Object>> options = List.of(
                Map.of("value", "read", "label", "读取", "category", "read", "destructive", "false"),
                Map.of("value", "write", "label", "写入", "category", "write", "destructive", "true"),
                Map.of("value", "inspect", "label", "检查", "category", "inspect", "destructive", "false"));
        return new ViewData(Map.of(
                "editor",
                dataSource(
                        Map.of(
                                "category", "read",
                                "destructive", false,
                                "byCategory", "read",
                                "byRisk", "inspect"),
                        List.of(),
                        3),
                "options",
                dataSource(Map.of(), options, 0)));
    }

    private static ViewField field(
            String name,
            String label,
            ViewFieldType type,
            ViewFieldValidation validation,
            List<ViewOption> options,
            Optional<ViewOptionSource> optionSource,
            Optional<ViewCondition> condition) {
        return new ViewField(
                name,
                label,
                type,
                new ViewBinding("editor", name),
                Optional.empty(),
                validation,
                options,
                optionSource,
                condition);
    }

    private static ViewSchema formSchema(String id, List<ViewField> fields, List<ViewDataSource> sources) {
        ViewAction save = new ViewAction(
                "保存",
                "definition/save",
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision("editor"),
                false);
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                id,
                "表单边界",
                sources,
                List.of(new ViewSchema.Form("form", "编辑", fields, save)));
    }

    private static ViewDataSource source(String id) {
        return new ViewDataSource(id, "view/" + id, Map.of(), List.of(), 20);
    }

    private static ViewData.Source dataSource(
            Map<String, Object> values, List<Map<String, Object>> rows, long revision) {
        return new ViewData.Source(rows, values, "", "", false, revision, 0, Optional.empty());
    }

    private static VBox render(ViewSchema schema, ViewData data, RecordingInteractions interactions) {
        return (VBox) new ViewSchemaRenderer().render(schema, data, interactions);
    }

    private static void assertFeedback(Node root, String expected) {
        assertTrue(nodes(root, Label.class).stream()
                .anyMatch(label -> label.getText().contains(expected)));
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ViewOption> combo(Node root, String accessibleText) {
        return (ComboBox<ViewOption>) control(root, ComboBox.class, accessibleText);
    }

    private static TextField text(Node root, String accessibleText) {
        return control(root, TextField.class, accessibleText);
    }

    private static CheckBox checkBox(Node root, String accessibleText) {
        return control(root, CheckBox.class, accessibleText);
    }

    private static Button button(Node root, String text) {
        return nodes(root, Button.class).stream()
                .filter(button -> text.equals(button.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static <T extends Node> T control(Node root, Class<T> type, String accessibleText) {
        return nodes(root, type).stream()
                .filter(node -> accessibleText.equals(node.getAccessibleText()))
                .findFirst()
                .orElseThrow();
    }

    private static <T extends Node> List<T> nodes(Node root, Class<T> type) {
        return descendants(root).stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static List<Node> descendants(Node root) {
        List<Node> result = new ArrayList<>();
        result.add(root);
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> result.addAll(descendants(child)));
        }
        return result;
    }

    private static final class RecordingInteractions implements ViewInteractionHandler {
        private final List<ViewCommandInvocation> commands = new ArrayList<>();

        @Override
        public void dirty(String formId, boolean dirty) {}

        @Override
        public void execute(ViewCommandInvocation invocation) {
            commands.add(invocation);
        }

        @Override
        public void reload() {}

        @Override
        public void page(String sourceId, ViewPageDirection direction) {}

        @Override
        public void select(String sourceId, Optional<String> selectedKey) {}

        @Override
        public CompletionStage<AttachmentRef> upload(ViewAttachmentUploadRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("本测试不上传文件"));
        }
    }
}
