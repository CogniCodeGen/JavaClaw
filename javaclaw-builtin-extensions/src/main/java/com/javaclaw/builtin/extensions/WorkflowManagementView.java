package com.javaclaw.builtin.extensions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.javaclaw.builtin.contracts.WorkflowContracts;
import com.javaclaw.builtin.contracts.WorkflowManagementContracts;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewFormField;
import com.javaclaw.extension.spi.ViewOption;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;
import com.javaclaw.extension.spi.ViewStructuredItemField;
import com.javaclaw.extension.spi.ViewStructuredItemType;
import com.javaclaw.extension.spi.ViewStructuredItemValidation;
import com.javaclaw.extension.spi.ViewStructuredListField;

/** 构造 Workflow 管理中心的安全 Graph 编辑页面。 */
final class WorkflowManagementView {
    private static final String NEW_SOURCE = "newDefinition";
    private static final String EDITOR_SOURCE = "definitionEditor";

    private final String viewId;

    WorkflowManagementView(String extensionId) {
        viewId = java.util.Objects.requireNonNull(extensionId, "extensionId") + ".management";
    }

    ViewSchema schema() {
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                viewId,
                "工作流定义",
                List.of(
                        new ViewDataSource("documents", "view.list", Map.of(), List.of(), 100),
                        new ViewDataSource(NEW_SOURCE, WorkflowManagement.VIEW_NEW, Map.of(), List.of(), 1),
                        new ViewDataSource(
                                EDITOR_SOURCE,
                                WorkflowManagement.VIEW_SELECTED,
                                Map.of(),
                                List.of(
                                        new ViewArgumentBinding("id", "documents", "id"),
                                        new ViewArgumentBinding("revision", "documents", "revision")),
                                1)),
                List.of(definitions(), createForm(), editForm()));
    }

    private ViewSchema.Table definitions() {
        ViewAction delete = new ViewAction(
                "删除", "delete", Map.of(), Map.of("id", "id"), new ExpectedRevisionBinding.RowField("revision"), true);
        return new ViewSchema.Table(
                "workflowDefinitions",
                "Workflow",
                "documents",
                "id",
                List.of(
                        new ViewSchema.Column("name", "名称", Optional.of(260)),
                        new ViewSchema.Column("maxVisits", "最大访问数", Optional.of(110)),
                        new ViewSchema.Column("revision", "版本", Optional.of(80))),
                ViewSelectionMode.SINGLE,
                List.of(delete));
    }

    private ViewSchema.Form createForm() {
        ViewAction save = new ViewAction(
                "创建 Workflow", WorkflowManagement.SAVE, Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false);
        return new ViewSchema.Form("workflow-create", "新建 Workflow", fields(NEW_SOURCE, true), save);
    }

    private ViewSchema.Form editForm() {
        ViewAction save = new ViewAction(
                "保存 Workflow",
                WorkflowManagement.SAVE,
                Map.of(),
                Map.of(),
                new ExpectedRevisionBinding.SourceRevision(EDITOR_SOURCE),
                false,
                new ViewCommandBinding("id", new ViewBinding(EDITOR_SOURCE, "id")));
        return new ViewSchema.Form("workflow-edit", "编辑所选 Workflow", fields(EDITOR_SOURCE, false), save);
    }

    private List<? extends ViewFormField> fields(String source, boolean includeId) {
        List<ViewFormField> fields = new java.util.ArrayList<>();
        if (includeId) {
            fields.add(text(source, "id", "Definition 标识", ViewFieldType.TEXT, true));
        }
        fields.add(text(source, "name", "名称", ViewFieldType.TEXT, true));
        fields.add(number(source, "maxVisits", "最大节点访问数", "100", 1, 10_000));
        fields.add(nodes(source));
        fields.add(toolArguments(source));
        fields.add(inputFields(source));
        fields.add(edges(source));
        return List.copyOf(fields);
    }

    private ViewStructuredListField nodes(String source) {
        return new ViewStructuredListField(
                "nodes",
                "Graph 节点",
                new ViewBinding(source, "nodes"),
                2,
                32,
                "id",
                nodeFields(),
                defaultNodes(),
                Optional.empty());
    }

    private List<ViewStructuredItemField> nodeFields() {
        return List.of(
                choice("kind", "类别", enumOptions(WorkflowContracts.NodeKind.values()), "TURN", true),
                item("name", "名称", ViewStructuredItemType.TEXT, true),
                item("instruction", "TURN 指令", ViewStructuredItemType.MULTILINE, false),
                item("toolName", "Tool 名称", ViewStructuredItemType.TEXT, false),
                item("resultField", "Tool 结果字段", ViewStructuredItemType.TEXT, false),
                item("conditionField", "条件字段", ViewStructuredItemType.TEXT, false),
                choice(
                        "conditionOperator",
                        "条件操作",
                        enumOptions(WorkflowContracts.ConditionOperator.values()),
                        "",
                        false),
                item("conditionExpected", "条件比较值", ViewStructuredItemType.TEXT, false),
                choice(
                        "transformOperation",
                        "变换操作",
                        enumOptions(WorkflowContracts.TransformOperation.values()),
                        "",
                        false),
                item("transformTarget", "变换目标", ViewStructuredItemType.TEXT, false),
                item("transformSource", "复制来源", ViewStructuredItemType.TEXT, false),
                item("transformValue", "固定值", ViewStructuredItemType.TEXT, false),
                item("inputPrompt", "输入问题", ViewStructuredItemType.MULTILINE, false),
                item("inputResponseField", "输入结果字段", ViewStructuredItemType.TEXT, false),
                itemNumber("inputTimeoutMinutes", "输入超时（分钟）", "0", 0, 1_440),
                item("outputField", "输出字段", ViewStructuredItemType.TEXT, false));
    }

    private ViewStructuredListField toolArguments(String source) {
        return new ViewStructuredListField(
                "toolArguments",
                "Tool 固定标量参数",
                new ViewBinding(source, "toolArguments"),
                0,
                64,
                "id",
                List.of(
                        item("nodeId", "TOOL 节点 ID", ViewStructuredItemType.TEXT, true),
                        item("name", "参数名", ViewStructuredItemType.TEXT, true),
                        choice(
                                "kind",
                                "值类型",
                                enumOptions(WorkflowManagementContracts.ScalarKind.values()),
                                "STRING",
                                true),
                        item("value", "固定值", ViewStructuredItemType.TEXT, true)),
                List.of(),
                Optional.empty());
    }

    private ViewStructuredListField inputFields(String source) {
        return new ViewStructuredListField(
                "inputFields",
                "用户输入响应字段",
                new ViewBinding(source, "inputFields"),
                0,
                64,
                "id",
                List.of(
                        item("nodeId", "USER_INPUT 节点 ID", ViewStructuredItemType.TEXT, true),
                        item("name", "字段名", ViewStructuredItemType.TEXT, true),
                        choice(
                                "kind",
                                "字段类型",
                                enumOptions(WorkflowManagementContracts.InputKind.values()),
                                "STRING",
                                true),
                        itemBoolean("required", "必填", false)),
                List.of(),
                Optional.empty());
    }

    private ViewStructuredListField edges(String source) {
        return new ViewStructuredListField(
                "edges",
                "Graph 边",
                new ViewBinding(source, "edges"),
                1,
                100,
                "id",
                List.of(
                        item("from", "起点 ID", ViewStructuredItemType.TEXT, true),
                        item("to", "终点 ID", ViewStructuredItemType.TEXT, true),
                        choice(
                                "branch",
                                "条件分支",
                                List.of(new ViewOption("TRUE", "TRUE"), new ViewOption("FALSE", "FALSE")),
                                "",
                                false)),
                defaultEdges(),
                Optional.empty());
    }

    private static ViewField text(String source, String name, String label, ViewFieldType type, boolean required) {
        return new ViewField(
                name,
                label,
                type,
                new ViewBinding(source, name),
                Optional.empty(),
                ViewFieldValidation.required(required),
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewField number(
            String source, String name, String label, String initial, int minimum, int maximum) {
        ViewFieldValidation validation = new ViewFieldValidation(
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.of(BigDecimal.valueOf(minimum)),
                Optional.of(BigDecimal.valueOf(maximum)),
                Optional.empty());
        return new ViewField(
                name,
                label,
                ViewFieldType.NUMBER,
                new ViewBinding(source, name),
                Optional.of(initial),
                validation,
                List.of(),
                Optional.empty(),
                Optional.empty());
    }

    private static ViewStructuredItemField item(
            String name, String label, ViewStructuredItemType type, boolean required) {
        return new ViewStructuredItemField(
                name,
                label,
                type,
                Optional.empty(),
                List.of(),
                ViewStructuredItemValidation.required(required),
                List.of());
    }

    private static ViewStructuredItemField itemBoolean(String name, String label, boolean initial) {
        return new ViewStructuredItemField(
                name,
                label,
                ViewStructuredItemType.BOOLEAN,
                Optional.of(Boolean.toString(initial)),
                List.of(),
                ViewStructuredItemValidation.required(true),
                List.of());
    }

    private static ViewStructuredItemField itemNumber(
            String name, String label, String initial, int minimum, int maximum) {
        ViewStructuredItemValidation validation = new ViewStructuredItemValidation(
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.of(BigDecimal.valueOf(minimum)),
                Optional.of(BigDecimal.valueOf(maximum)),
                Optional.empty(),
                Optional.empty());
        return new ViewStructuredItemField(
                name, label, ViewStructuredItemType.NUMBER, Optional.of(initial), List.of(), validation, List.of());
    }

    private static ViewStructuredItemField choice(
            String name, String label, List<ViewOption> options, String initial, boolean required) {
        return new ViewStructuredItemField(
                name,
                label,
                ViewStructuredItemType.CHOICE,
                initial.isEmpty() ? Optional.empty() : Optional.of(initial),
                List.of(),
                ViewStructuredItemValidation.required(required),
                options);
    }

    private static List<ViewOption> enumOptions(Enum<?>[] values) {
        return java.util.Arrays.stream(values)
                .map(value -> new ViewOption(value.name(), value.name()))
                .toList();
    }

    private static List<Map<String, Object>> defaultNodes() {
        return List.of(defaultNode("start", "START", "开始"), defaultNode("end", "END", "结束"));
    }

    private static Map<String, Object> defaultNode(String id, String kind, String name) {
        return Map.ofEntries(
                Map.entry("id", id),
                Map.entry("kind", kind),
                Map.entry("name", name),
                Map.entry("instruction", ""),
                Map.entry("toolName", ""),
                Map.entry("resultField", ""),
                Map.entry("conditionField", ""),
                Map.entry("conditionOperator", ""),
                Map.entry("conditionExpected", ""),
                Map.entry("transformOperation", ""),
                Map.entry("transformTarget", ""),
                Map.entry("transformSource", ""),
                Map.entry("transformValue", ""),
                Map.entry("inputPrompt", ""),
                Map.entry("inputResponseField", ""),
                Map.entry("inputTimeoutMinutes", BigDecimal.ZERO),
                Map.entry("outputField", ""));
    }

    private static List<Map<String, Object>> defaultEdges() {
        return List.of(Map.of("id", "start-end", "from", "start", "to", "end", "branch", ""));
    }
}
