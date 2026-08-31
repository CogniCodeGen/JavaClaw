package com.javaclaw.desktop;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.ToolAuthorityOptionInfo;
import com.javaclaw.sdk.model.ToolAuthorizationInfo;

/** 原设置表单中的无人值守通信许可；用户明确确认完整范围，不能一键授予任意网络或宿主权限。 */
final class ToolAuthorizationPane extends BorderPane {
    private final ManagementViewModel model;
    private final ListView<ToolAuthorizationInfo> grants =
            ManagementForms.list(value -> value.toolName() + "\n" + value.consumedUses() + "/" + value.maximumUses()
                    + " · " + (value.enabled() ? "有效至 " + value.expiresAt() : "已撤销"));
    private final BorderPane detail = new BorderPane();
    private List<ToolAuthorityOptionInfo> options = List.of();

    ToolAuthorizationPane(ManagementViewModel model) {
        this.model = model;
        setCenter(ManagementForms.split(grants, detail));
        setTop(ManagementForms.actions(
                ManagementForms.command("＋ 有限预授权", UiActionKind.PRIMARY, model, () -> edit(null)),
                ManagementForms.command("刷新", UiActionKind.GHOST, model, this::reload)));
        grants.getSelectionModel().selectedItemProperty().addListener((ignored, old, value) -> {
            if (value != null) {
                edit(value);
            }
        });
        detail.setCenter(ManagementForms.form(
                ManagementForms.hint("仅用于当前工作区的无人值守 Schedule。普通 Shell、HOST_FULL_ACCESS 和任意网络始终不支持预授权。")));
        reload();
    }

    static void show(Node owner, ManagementViewModel model) {
        var dialog = new Dialog<Void>();
        dialog.setTitle("无人值守通信预授权");
        dialog.getDialogPane().setContent(new ToolAuthorizationPane(model));
        dialog.getDialogPane().setPrefSize(960, 700);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ManagementForms.style(owner, dialog);
        dialog.showAndWait();
    }

    private void reload() {
        String workspace = model.workspaceId();
        model.execute(
                "读取预授权",
                sdk -> sdk.extensions()
                        .toolAuthorizationOptions(workspace)
                        .thenCombine(sdk.extensions().listToolAuthorizations(workspace), Catalog::new),
                catalog -> {
                    options = catalog.options();
                    grants.getItems().setAll(catalog.grants());
                });
    }

    private void edit(ToolAuthorizationInfo original) {
        if (original == null && options.isEmpty()) {
            detail.setCenter(ManagementForms.form(
                    ManagementForms.hint("尚无当前工作区的有效 MCP 工具目录。配置连接后先完成一次工作区对话，使真实发现结果可供审阅；不会根据虚构工具名建立许可。")));
            return;
        }
        var selected = original == null
                ? options.getFirst()
                : options.stream()
                        .filter(value -> value.sourceId().equals(original.sourceId())
                                && value.toolName().equals(original.toolName())
                                && value.sourceRevision() == original.sourceRevision()
                                && value.schemaSha256().equals(original.schemaSha256()))
                        .findFirst()
                        .orElse(null);
        var tool = ManagementForms.choices(
                options, value -> value.toolName() + " · 连接版本 " + value.sourceRevision(), selected);
        tool.setDisable(original != null);
        var schema = ManagementForms.area(
                selected == null
                        ? "连接/Schema 已变化：请撤销旧许可并重新审阅。"
                        : selected.inputSchema().canonicalJson(),
                5);
        schema.setEditable(false);
        tool.valueProperty()
                .addListener((ignored, old, value) ->
                        schema.setText(value == null ? "" : value.inputSchema().canonicalJson()));
        var template = ManagementForms.area(
                original == null ? "{}" : original.argumentTemplate().canonicalJson(), 7);
        var receiver = ManagementForms.text(
                original == null ? "" : original.recipientField(), "Schema 中固定接收对象字段，如 to 或 channel");
        var variables = ManagementForms.text(
                original == null ? "" : String.join(", ", original.variableFields()),
                "仅允许 subject/body/text/message/content/html，逗号分隔");
        var uses = ManagementForms.number(original == null ? 1 : original.maximumUses(), 100);
        var expires = ManagementForms.text(
                original == null
                        ? Instant.now().plusSeconds(3600).toString()
                        : original.expiresAt().toString(),
                "ISO-8601，最多三十天");
        var save = ManagementForms.command("审阅并授权", UiActionKind.PRIMARY, model, () -> {
            try {
                ToolAuthorityOptionInfo option = tool.getValue();
                if (option == null) {
                    throw new IllegalArgumentException("工具版本已失效");
                }
                var variableFields = java.util.Arrays.stream(variables.getText().split("[,\\s]+"))
                        .filter(value -> !value.isBlank())
                        .collect(Collectors.toSet());
                var value = new ToolAuthorizationInfo(
                        original == null ? null : original.id(),
                        model.workspaceId(),
                        option.sourceId(),
                        option.toolName(),
                        option.sourceRevision(),
                        option.schemaSha256(),
                        new JsonDocument(template.getText()),
                        receiver.getText().strip(),
                        variableFields,
                        uses.getValue(),
                        original == null ? 0 : original.consumedUses(),
                        Instant.parse(expires.getText().strip()),
                        true,
                        original == null ? 0 : original.revision(),
                        null);
                if (!ManagementForms.confirm(
                        this,
                        model,
                        "确认有限通信许可",
                        value.toolName() + "\n连接版本 " + value.sourceRevision() + "\n"
                                + value.argumentTemplate().canonicalJson()
                                + "\n可变字段：" + value.variableFields() + "\n总次数：" + value.maximumUses()
                                + "\n有效至：" + value.expiresAt()
                                + "\n\n其他参数必须完全一致。结果未知不自动重发；外部失败也可能消耗一次额度。")) {
                    return;
                }
                model.execute(
                        "保存预授权",
                        sdk -> sdk.extensions()
                                .saveToolAuthorization(value, true, ManagementViewModel.key("tool-authorize")),
                        saved -> {
                            reload();
                            edit(saved);
                        });
            } catch (IllegalArgumentException invalid) {
                ManagementForms.showText(this, "请检查授权范围", "检查参数 JSON、固定接收字段、次数及 ISO-8601 到期时间。");
            }
        });
        var revoke = ManagementForms.command("立即撤销", UiActionKind.DANGER, model, () -> {
            if (original != null && ManagementForms.confirm(this, model, "撤销许可", "下一次操作不能再使用本许可；不会删除已执行操作的凭据。")) {
                model.execute(
                        "撤销许可",
                        sdk -> sdk.extensions()
                                .disableToolAuthorization(
                                        original.id(), original.revision(), ManagementViewModel.key("tool-revoke")),
                        ignored -> reload());
            }
        });
        revoke.setVisible(original != null);
        revoke.setManaged(original != null);
        detail.setCenter(ManagementForms.scroll(ManagementForms.form(
                ManagementForms.field("真实工具与来源", tool),
                ManagementForms.field("输入 Schema", schema),
                ManagementForms.field("完整参数模板", template),
                ManagementForms.field("固定接收字段", receiver),
                ManagementForms.field("允许变化的文本字段", variables),
                ManagementForms.field("总次数（已使用次数不会归零）", uses),
                ManagementForms.field("到期时间", expires),
                ManagementForms.actions(save, revoke))));
    }

    private record Catalog(List<ToolAuthorityOptionInfo> options, List<ToolAuthorizationInfo> grants) {}
}
