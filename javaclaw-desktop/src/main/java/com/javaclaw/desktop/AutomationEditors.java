package com.javaclaw.desktop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.javaclaw.sdk.model.AutomationDefinitionInfo;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.ProfileInfo;

/** 原 Workflow 侧栏表单风格的节点与验收编辑器；只编辑声明，禁止任意表达式代码。 */
final class AutomationEditors {
    private AutomationEditors() {}

    static void criterion(
            Node owner, AutomationDefinitionInfo.Criterion value, Consumer<AutomationDefinitionInfo.Criterion> save) {
        var id = ManagementForms.text(value.id(), "稳定标识");
        var description = ManagementForms.text(value.description(), "如何判断成功");
        var kind = ManagementForms.choices(
                List.of("USER_CONFIRMATION", "COMMAND_EXIT", "RESULT_FIELD"),
                item -> switch (item) {
                    case "COMMAND_EXIT" -> "实际命令退出码";
                    case "RESULT_FIELD" -> "工具结果字段";
                    default -> "用户显式确认";
                },
                value.kind());
        var tool = ManagementForms.text(value.tool(), "本 Turn 可见的验收工具名称");
        var arguments = ManagementForms.area(value.arguments().canonicalJson(), 5);
        var field = ManagementForms.text(value.field(), "工具结果字段");
        var expected = ManagementForms.text(value.expected(), "预期值；退出码成功为 0");
        var form = ManagementForms.form(
                ManagementForms.field("标识", id),
                ManagementForms.field("成功标准", description),
                ManagementForms.field("验收方式", kind),
                ManagementForms.field("工具名称", tool),
                ManagementForms.field("工具参数 JSON", arguments),
                ManagementForms.field("结果字段", field),
                ManagementForms.field("预期值", expected),
                ManagementForms.hint("验收工具也经过 Schema、审批和沙箱。助手自述“完成”不是成功证据。"));
        form.setPrefWidth(560);
        ManagementForms.edit(
                owner,
                "验收条件",
                form,
                () -> new AutomationDefinitionInfo.Criterion(
                        id.getText(),
                        description.getText(),
                        kind.getValue(),
                        tool.getText(),
                        new JsonDocument(arguments.getText()),
                        field.getText(),
                        expected.getText()),
                save);
    }

    static void node(
            Node owner,
            AutomationDefinitionInfo.Node original,
            List<ProfileInfo> profiles,
            Consumer<AutomationDefinitionInfo.Node> save) {
        var id = ManagementForms.text(original.id(), "图中唯一标识");
        var kind = ManagementForms.choices(
                List.of("START", "END", "AGENT", "TOOL", "CONDITION", "TRANSFORM", "HUMAN_INPUT", "OUTPUT"),
                value -> value,
                original.kind());
        var next = ManagementForms.text(original.next(), "正常后继 id");
        var otherwise = ManagementForms.text(original.otherwise(), "条件为假时的后继 id");
        var visits = ManagementForms.number(original.maxVisits(), 100);
        var parameterForm = new VBox(10);
        var parameters = new LinkedHashMap<String, java.util.function.Supplier<String>>();
        Runnable render = () -> {
            parameterForm.getChildren().clear();
            parameters.clear();
            Map<String, String> values = original.kind().equals(kind.getValue()) ? original.parameters() : Map.of();
            for (var name :
                    switch (kind.getValue()) {
                        case "AGENT" -> List.of("task", "profileId", "input", "writable");
                        case "TOOL" -> List.of("tool", "arguments");
                        case "CONDITION" -> List.of("input", "operator", "expected");
                        case "TRANSFORM" -> List.of("input", "operation", "value");
                        case "HUMAN_INPUT" -> List.of("prompt");
                        case "OUTPUT" -> List.of("input", "name");
                        default -> List.<String>of();
                    }) {
                Node input;
                if (name.equals("profileId")) {
                    var childProfiles = profiles.stream()
                            .filter(profile -> "SUBAGENT".equals(profile.kind()))
                            .toList();
                    var selector = ManagementForms.choices(
                            childProfiles,
                            ProfileInfo::name,
                            childProfiles.stream()
                                    .filter(profile -> profile.id().equals(values.get(name)))
                                    .findFirst()
                                    .orElse(childProfiles.isEmpty() ? null : childProfiles.getFirst()));
                    parameters.put(
                            name,
                            () -> selector.getValue() == null
                                    ? ""
                                    : selector.getValue().id());
                    input = selector;
                } else if (name.equals("writable")) {
                    var check = ManagementForms.check("写型子任务（独立工作树，补丁不自动合并）", Boolean.parseBoolean(values.get(name)));
                    parameters.put(name, () -> Boolean.toString(check.isSelected()));
                    input = check;
                } else if (name.equals("operator") || name.equals("operation")) {
                    var choices = name.equals("operator")
                            ? List.of("equals", "notEquals", "contains", "isEmpty")
                            : List.of("constant", "copy", "trim", "uppercase", "lowercase", "append");
                    var selector = ManagementForms.choices(
                            choices, item -> item, values.getOrDefault(name, choices.getFirst()));
                    parameters.put(name, selector::getValue);
                    input = selector;
                } else if (List.of("task", "prompt", "value", "arguments").contains(name)) {
                    TextArea text =
                            ManagementForms.area(values.getOrDefault(name, name.equals("arguments") ? "{}" : ""), 4);
                    parameters.put(name, text::getText);
                    input = text;
                } else {
                    TextField text = ManagementForms.text(values.getOrDefault(name, ""), name);
                    parameters.put(name, text::getText);
                    input = text;
                }
                parameterForm
                        .getChildren()
                        .add(ManagementForms.field(
                                switch (name) {
                                    case "task" -> "节点任务";
                                    case "profileId" -> "子智能体 Profile";
                                    case "input" -> "输入节点 id";
                                    case "arguments" -> "工具参数 JSON（可用 {\"$output\":\"节点 id\"} 引用结果）";
                                    case "tool" -> "工具名称";
                                    case "operator" -> "条件操作";
                                    case "operation" -> "确定性转换";
                                    case "expected" -> "预期字符串";
                                    case "value" -> "常量 / 附加值";
                                    case "prompt" -> "用户输入问题";
                                    case "name" -> "产物名称";
                                    default -> name;
                                },
                                input));
            }
        };
        kind.valueProperty().addListener((ignored, old, value) -> render.run());
        render.run();
        var form = ManagementForms.form(
                ManagementForms.field("节点 id", id),
                ManagementForms.field("类型", kind),
                ManagementForms.field("下一节点", next),
                ManagementForms.field("条件另一分支", otherwise),
                ManagementForms.field("最多访问次数（循环必须大于 0）", visits),
                parameterForm);
        form.setPrefWidth(640);
        ManagementForms.edit(
                owner,
                "Workflow 节点",
                ManagementForms.scroll(form),
                () -> {
                    var values = new LinkedHashMap<String, String>();
                    parameters.forEach((name, supplier) -> {
                        String value = supplier.get();
                        if (!value.isBlank()
                                || List.of("arguments", "value", "expected").contains(name)) {
                            values.put(name, value);
                        }
                    });
                    return new AutomationDefinitionInfo.Node(
                            id.getText(),
                            kind.getValue(),
                            next.getText(),
                            otherwise.getText(),
                            visits.getValue(),
                            values);
                },
                save);
    }
}
