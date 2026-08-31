package com.javaclaw.desktop;

import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

import com.javaclaw.sdk.model.AgentsInstructionResolutionInfo;
import com.javaclaw.sdk.model.AgentsInstructionSourceInfo;

/** AGENTS.md 唯一文件源的只读状态页；不导入、编辑、导出或保存正文副本。 */
final class AgentsInstructionsPane extends ManagedManagementPage {
    private final ListView<AgentsInstructionSourceInfo> sources;
    private final BorderPane detail = new BorderPane();

    /** 创建遵循现有管理页布局的只读解析视图，并立即读取所选工作区状态。 */
    AgentsInstructionsPane(ManagementViewModel model) {
        super(model);
        sources = ManagementForms.list(
                value -> scope(value.scope()) + " · "
                        + DesktopPresentationMapper.workspacePath(model.workspaceRoot(), value.path())
                        + (value.truncated() ? "\n已按上下文预算截断" : ""),
                "未发现项目约定",
                "在工作区层级添加 AGENTS.md、AGENTS.override.md 或配置的 fallback 文件后刷新。");
        setTop(ManagementForms.actions(ManagementForms.command("刷新", UiActionKind.GHOST, model, this::reload)));
        setCenter(ManagementForms.split(sources, detail));
        sources.getSelectionModel().selectedItemProperty().addListener((observable, prior, value) -> show(value));
        detail.setCenter(ManagementForms.emptyState(
                "A", "项目约定解析状态", "JavaClaw 按 AGENTS.override.md、AGENTS.md 和 fallback 规则解析；正文不会经管理协议返回。"));
        reload();
    }

    private void reload() {
        refreshing();
        model.execute(
                "解析 AGENTS.md",
                sdk -> sdk.workspaces().resolveInstructions(model.workspaceId()),
                value -> {
                    showResolution(value);
                    ready();
                },
                ignored -> loadFailed());
    }

    private void showResolution(AgentsInstructionResolutionInfo value) {
        sources.getItems().setAll(value.sources());
        String warnings = value.warnings().isEmpty() ? "无解析警告" : String.join("\n", value.warnings());
        detail.setCenter(ManagementForms.form(ManagementForms.section(
                "解析摘要",
                ManagementForms.hint("解析目录："
                        + DesktopPresentationMapper.workspacePath(model.workspaceRoot(), value.workingDirectory())),
                ManagementForms.hint("项目层已使用 " + DesktopPresentationMapper.bytes(value.totalProjectBytes()) + "。"),
                ManagementForms.hint(warnings))));
        if (!sources.getItems().isEmpty()) {
            sources.getSelectionModel().selectFirst();
        }
    }

    private void show(AgentsInstructionSourceInfo value) {
        if (value == null) {
            return;
        }
        int priority = sources.getItems().indexOf(value) + 1;
        var technical = ManagementForms.button(
                "查看完整技术信息",
                UiActionKind.GHOST,
                () -> ManagementForms.showText(
                        this, "AGENTS.md 技术信息", "绝对路径：" + value.path() + "\nSHA-256：" + value.sha256()));
        detail.setCenter(ManagementForms.form(ManagementForms.section(
                "约定摘要",
                ManagementForms.hint("范围：" + scope(value.scope()) + " · 覆盖顺序：第 " + priority + " 项"),
                ManagementForms.hint(
                        "路径：" + DesktopPresentationMapper.workspacePath(model.workspaceRoot(), value.path())),
                ManagementForms.hint("内容摘要：" + DesktopPresentationMapper.shortHash(value.sha256())),
                ManagementForms.hint("注入容量：" + DesktopPresentationMapper.bytes(value.bytes())
                        + (value.truncated() ? "（已按项目上下文预算截断）" : "（完整）")),
                ManagementForms.hint("正文只在模型上下文边界内读取，不通过管理协议返回。活动 Turn 使用启动时快照。"),
                technical)));
    }

    private static String scope(String value) {
        return "global".equals(value) ? "全局约定" : "项目约定";
    }
}
