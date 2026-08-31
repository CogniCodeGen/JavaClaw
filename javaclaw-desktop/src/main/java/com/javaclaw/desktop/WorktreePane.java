package com.javaclaw.desktop;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;

import com.javaclaw.sdk.model.WorktreeInfo;

/** 原管理窗口中的协作恢复页；只使用 typed SDK，补丁合并仍回到父 Turn 的审批工具链。 */
final class WorktreePane extends ManagedManagementPage {
    private final ListView<WorktreeInfo> worktrees = ManagementForms.list(
            value -> DesktopPresentationMapper.status(value.state()) + " · " + value.id(),
            "没有待恢复的工作树",
            "子智能体产生隔离写入、冲突或崩溃恢复记录后，会显示在这里。");
    private final BorderPane detail = new BorderPane();

    WorktreePane(ManagementViewModel model) {
        super(model);
        setTop(ManagementForms.actions(ManagementForms.command("刷新", UiActionKind.GHOST, model, this::reload)));
        setCenter(ManagementForms.split(worktrees, detail));
        detail.setCenter(ManagementForms.emptyState("⌘", "协作工作树恢复", "查看子任务、冲突和补丁。合并必须由父任务显式审批，不会修改用户真实 Git index。"));
        worktrees.getSelectionModel().selectedItemProperty().addListener((observable, prior, value) -> {
            if (value != null) {
                show(value);
            }
        });
        reload();
    }

    private void reload() {
        refreshing();
        String workspace = model.workspaceId();
        String selectedId = worktrees.getSelectionModel().getSelectedItem() == null
                ? null
                : worktrees.getSelectionModel().getSelectedItem().id();
        model.execute(
                "读取工作树恢复记录",
                sdk -> sdk.threads().worktrees(workspace),
                values -> {
                    worktrees.getItems().setAll(values);
                    var selected = values.stream()
                            .filter(value -> value.id().equals(selectedId))
                            .findFirst()
                            .orElse(values.isEmpty() ? null : values.getFirst());
                    if (selected != null) {
                        worktrees.getSelectionModel().select(selected);
                    }
                    ready();
                },
                ignored -> loadFailed());
    }

    private void show(WorktreeInfo value) {
        var export = ManagementForms.command("导出当前补丁…", model, () -> export(value, false));
        var cleanup = ManagementForms.command("清理隔离工作树…", UiActionKind.DANGER, model, () -> cleanup(value));
        if (value.running() || "CLEANED".equals(value.state())) {
            ManagementForms.guard(export, true);
            ManagementForms.guard(cleanup, true);
        }
        var form = ManagementForms.form(
                ManagementForms.hint("工作树 " + value.id() + " · 修订 " + value.revision()),
                ManagementForms.hint(value.details()),
                ManagementForms.actions(
                        ManagementForms.command("查看子任务", model, () -> model.showThread(value.childThreadId())),
                        ManagementForms.command("返回父任务处理合并", model, () -> model.showThread(value.parentThreadId()))),
                ManagementForms.actions(export, cleanup),
                ManagementForms.hint(
                        "最多显示最近 256 条记录。清理前保存 tracked 和非 ignored untracked 文件的 binary patch；ignored 临时文件不会进入备份。"));
        if (value.running()) {
            form.getChildren().add(ManagementForms.command("中断子任务", UiActionKind.DANGER, model, () -> {
                if (!ManagementForms.confirm(this, model, "中断子任务", "请求取消后仍需等待进程退出；不会自动删除工作树。")) {
                    return;
                }
                model.execute(
                        "中断子任务",
                        sdk -> sdk.threads()
                                .read(value.childThreadId())
                                .thenCompose(snapshot -> CompletableFuture.allOf(snapshot.turns().stream()
                                        .filter(turn -> turn.completedAt() == null)
                                        .map(turn -> sdk.threads().interrupt(turn.id()))
                                        .toArray(CompletableFuture[]::new))),
                        ignored -> reload());
            }));
        }
        if (value.backupSha256() != null) {
            form.getChildren().add(ManagementForms.command("导出保留的备份…", model, () -> export(value, true)));
        }
        detail.setCenter(ManagementForms.scroll(form));
    }

    private void export(WorktreeInfo value, boolean backup) {
        var file = model.dialogs()
                .chooseSaveFile(
                        this,
                        backup ? "导出工作树备份" : "导出工作树补丁",
                        value.id() + (backup ? "-backup.patch" : ".patch"),
                        List.of(new DesktopDialogGateway.FileType("Git patch", List.of("*.patch", "*.diff"))))
                .orElse(null);
        if (file == null) {
            return;
        }
        model.execute(
                "导出补丁",
                sdk -> {
                    var hash = backup
                            ? CompletableFuture.completedFuture(value.backupSha256())
                            : sdk.threads()
                                    .exportWorktreePatch(value.childThreadId(), value.revision())
                                    .thenApply(result -> result.patchAttachmentSha256());
                    return hash.thenCompose(sha -> sha == null
                            ? CompletableFuture.completedFuture(null)
                            : sdk.attachments().download(sha, file, false));
                },
                path -> ManagementForms.confirm(
                        this, model, "导出结果", path == null ? "当前工作树没有变更。" : "补丁已导出到所选文件。未应用到工作区；如需合并，请回到父任务确认。"));
    }

    private void cleanup(WorktreeInfo value) {
        if (!ManagementForms.confirm(
                this, model, "确认清理工作树", "将删除这个隔离工作树，包括尚未合并的文件。服务端先留存 Git 补丁备份；ignored 文件不备份。父工作区和真实 index 不受影响。")) {
            return;
        }
        model.execute(
                "清理工作树",
                sdk -> sdk.threads()
                        .cleanupWorktree(
                                value.childThreadId(),
                                value.revision(),
                                true,
                                ManagementViewModel.key("worktree-cleanup")),
                result -> {
                    reload();
                    show(result);
                });
    }
}
