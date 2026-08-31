package com.javaclaw.desktop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import com.javaclaw.sdk.model.AttachmentInfo;
import com.javaclaw.sdk.model.JsonDocument;

/** 原设置导航壳的 SDK-only 实现；只展示 4.0 支持的外观、云模型、连接和脱敏诊断。 */
final class SettingsPane extends ManagedManagementPage {
    private final DesktopViewModel desktop;
    private final ListView<Section> sections = ManagementForms.list(Section::title);
    private final BorderPane detail = new BorderPane();

    SettingsPane(ManagementViewModel model, DesktopViewModel desktop) {
        super(model);
        this.desktop = desktop;
        sections.getItems()
                .setAll(
                        new Section("appearance", "外观与主题", "沿用原版九套主题"),
                        new Section("providers", "云模型", "OpenAI、Anthropic、Google"),
                        new Section("connection", "连接与诊断", "App Server 状态和脱敏诊断"));
        sections.getStyleClass().add("settings-navigation-list");
        sections.setFixedCellSize(36);
        double navigationHeight =
                sections.getFixedCellSize() * sections.getItems().size() + 8;
        sections.setMinHeight(navigationHeight);
        sections.setPrefHeight(navigationHeight);
        sections.setMaxHeight(navigationHeight);
        sections.setCellFactory(ignored -> new javafx.scene.control.ListCell<>() {
            private final Label label = new Label();

            {
                getStyleClass().add("modal-nav-btn");
                label.getStyleClass().addAll("modal-nav-plain", "settings-navigation-title");
                label.maxWidthProperty().bind(widthProperty().subtract(22));
            }

            @Override
            protected void updateItem(Section value, boolean empty) {
                super.updateItem(value, empty);
                label.setText(empty || value == null ? "" : value.title());
                label.setTooltip(empty || value == null ? null : new Tooltip(value.description()));
                setAccessibleText(empty || value == null ? null : value.title() + "，" + value.description());
                setText(null);
                setGraphic(empty || value == null ? null : label);
            }
        });
        guardSelection(sections, value -> show(value.id()));
        var group = new Label("▾  设置分类");
        group.getStyleClass().addAll("settings-nav-group", "modal-nav-group");
        var children = new VBox(sections);
        children.getStyleClass().addAll("settings-navigation-children", "modal-nav-children");
        var navigation = new VBox(4, group, children);
        navigation.getStyleClass().add("settings-navigation");
        var split = ManagementForms.split(navigation, detail);
        split.getStyleClass().add("settings-root");
        split.getLeft().getStyleClass().add("settings-left-pane");
        setCenter(split);
        sections.getSelectionModel().selectFirst();
    }

    private void show(String id) {
        switch (id) {
            case "appearance" -> {
                delegateTo(null);
                appearance();
                ready();
            }
            case "providers" -> {
                var provider = new ProviderPane(model);
                delegateTo(provider);
                detail.setCenter(provider);
            }
            case "connection" -> {
                delegateTo(null);
                connection();
                ready();
            }
            default -> {
                delegateTo(null);
                detail.setCenter(ManagementForms.emptyState("⚙", "设置不可用", "请选择左侧设置分类。"));
                ready();
            }
        }
    }

    private void appearance() {
        var selected = new Label();
        selected.textProperty()
                .bind(Bindings.createStringBinding(
                        () -> "当前主题：" + themeName(desktop.themeProperty().get()), desktop.themeProperty()));
        selected.getStyleClass().add("management-selection-summary");
        var choices = new FlowPane(10, 10);
        choices.setPadding(new Insets(4, 0, 4, 0));
        for (DesktopTheme.Choice choice : DesktopTheme.choices()) {
            var button =
                    ManagementForms.button(choice.name(), UiActionKind.SECONDARY, () -> desktop.setTheme(choice.id()));
            button.setMinWidth(112);
            button.disableProperty().bind(desktop.themeProperty().isEqualTo(choice.id()));
            button.setAccessibleText("切换到" + choice.name() + "主题");
            choices.getChildren().add(button);
        }
        detail.setCenter(ManagementForms.scroll(ManagementForms.form(
                ManagementForms.section(
                        "主题", selected, choices, ManagementForms.hint("保留原版九个主题、字体、圆角和间距。切换后主窗口、管理页和新打开的对话框同步更新。")),
                ManagementForms.section(
                        "界面范围",
                        ManagementForms.hint("JavaClaw 4.0 不显示 Ollama、Deliverance 或本地模型资产页面。技术架构升级不会替换原桌面视觉语言。")))));
    }

    private void connection() {
        var state = new Label();
        state.textProperty().bind(desktop.connectionProperty());
        state.getStyleClass().add("management-status-badge");
        var diagnosticsSummary = ManagementForms.hint("尚未读取诊断。诊断视图和导出包均由 App Server 脱敏，不包含凭据或环境变量。 ");
        var diagnostics = new AtomicReference<JsonDocument>();
        var refresh = ManagementForms.command(
                "刷新诊断",
                UiActionKind.PRIMARY,
                model,
                () -> model.execute("读取脱敏诊断", sdk -> sdk.administration().readDiagnostics(100), value -> {
                    diagnostics.set(value);
                    diagnosticsSummary.setText("诊断已更新 · "
                            + DesktopPresentationMapper.bytes(
                                    value.canonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                            + " · 凭据和环境变量未包含");
                }));
        var inspect = ManagementForms.button("查看脱敏技术详情", UiActionKind.GHOST, () -> {
            JsonDocument value = diagnostics.get();
            if (value == null) {
                model.validationError("请先刷新诊断");
            } else {
                // 技术 JSON 只在用户显式请求的详情弹窗中展示，普通设置页保持领域文案。
                ManagementForms.showText(this, "脱敏诊断详情", value.canonicalJson());
            }
        });
        var export = ManagementForms.command("导出诊断包…", UiActionKind.SECONDARY, model, this::exportDiagnostics);
        detail.setCenter(ManagementForms.scroll(ManagementForms.form(
                ManagementForms.section(
                        "App Server 连接",
                        ManagementForms.hint("连接状态"),
                        state,
                        ManagementForms.hint("工作区：" + model.workspaceName())),
                ManagementForms.section(
                        "脱敏诊断",
                        diagnosticsSummary,
                        ManagementForms.actions(refresh, inspect, export),
                        ManagementForms.hint("导出包为内容寻址附件。保存目标只由本机 SDK 使用，不会发送给 App Server。")))));
    }

    private void exportDiagnostics() {
        model.execute(
                "生成脱敏诊断包",
                sdk -> sdk.administration().exportDiagnostics(ManagementViewModel.key("diagnostics-export")),
                attachment -> {
                    Path target = model.dialogs()
                            .chooseSaveFile(
                                    this,
                                    "导出 JavaClaw 诊断包",
                                    "javaclaw-diagnostics.zip",
                                    List.of(new DesktopDialogGateway.FileType("ZIP 诊断包", List.of("*.zip"))))
                            .orElse(null);
                    if (target == null) {
                        release(attachment);
                        return;
                    }
                    boolean replace = Files.exists(target);
                    if (replace && !ManagementForms.confirm(this, model, "覆盖诊断包", "将替换所选文件：" + target)) {
                        release(attachment);
                        return;
                    }
                    model.execute(
                            "保存诊断包",
                            sdk -> sdk.attachments()
                                    .download(attachment.sha256(), target, replace)
                                    .thenCompose(path -> sdk.attachments()
                                            .release(attachment.sha256())
                                            .handle((ignored, failure) -> path)),
                            path -> ManagementForms.showText(this, "诊断包已保存", path.toString()));
                });
    }

    private void release(AttachmentInfo attachment) {
        model.execute("释放未保存的诊断包", sdk -> sdk.attachments().release(attachment.sha256()), ignored -> {});
    }

    private static String themeName(String id) {
        return DesktopTheme.choices().stream()
                .filter(value -> value.id().equals(id))
                .map(DesktopTheme.Choice::name)
                .findFirst()
                .orElse("翡翠");
    }

    private record Section(String id, String title, String description) {}
}
