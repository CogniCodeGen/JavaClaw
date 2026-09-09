package com.javaclaw.desktop.shell;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextInputDialog;
import javafx.stage.DirectoryChooser;

import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.DesktopPresenter;
import com.javaclaw.desktop.component.PlatformDialogs;
import com.javaclaw.desktop.settings.ExecutionSelectionPanel;
import com.javaclaw.desktop.settings.SdkCoreSettingsGateway;

/** 创建工作区的本地交互；已知目录直接提示打开，服务端仍负责权威查重及并发约束。 */
final class ShellWorkspaceCreation {
    private static final ButtonType OPEN = new ButtonType("打开已有工作区", ButtonBar.ButtonData.OK_DONE);
    private static final ButtonType CHOOSE_ANOTHER = new ButtonType("选择其他目录", ButtonBar.ButtonData.OTHER);

    private ShellWorkspaceCreation() {}

    static void show(Node owner, DesktopPresenter presenter, Supplier<List<Workspace>> workspaces) {
        TextInputDialog nameDialog = PlatformDialogs.requiredText(
                owner,
                "创建工作区",
                "设置工作区名称",
                "名称用于在 JavaClaw 中识别工作区；下一步选择本地根目录。已有目录可直接打开对应工作区。",
                "例如：JavaClaw 开发",
                "新工作区",
                "继续");
        Optional<String> name = nameDialog.showAndWait().map(String::strip).filter(value -> !value.isEmpty());
        if (name.isEmpty()) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择工作区根目录");
        chooseDirectory(
                        owner,
                        () -> Optional.ofNullable(
                                        chooser.showDialog(owner.getScene().getWindow()))
                                .map(File::toPath),
                        workspaces,
                        presenter::selectWorkspace)
                .ifPresent(directory -> chooseExecution(owner, presenter, name.orElseThrow(), directory));
    }

    /** 只使用主窗口已有目录快照，不因打开创建入口重复读取配置；未知的并发登记由服务端拒绝。 */
    static Optional<Path> chooseDirectory(
            Node owner,
            Supplier<Optional<Path>> selection,
            Supplier<List<Workspace>> workspaces,
            Consumer<Workspace> open) {
        while (true) {
            Optional<Path> directory =
                    selection.get().map(path -> path.toAbsolutePath().normalize());
            if (directory.isEmpty()) {
                return Optional.empty();
            }
            Optional<Workspace> existing = workspaces.get().stream()
                    .filter(workspace -> workspace.root().equals(directory.orElseThrow()))
                    .findFirst();
            if (existing.isEmpty()) {
                return directory;
            }
            Workspace workspace = existing.orElseThrow();
            ButtonType action = existingDialog(owner, workspace).showAndWait().orElse(ButtonType.CANCEL);
            if (action == OPEN) {
                open.accept(workspace);
            }
            if (action != CHOOSE_ANOTHER) {
                return Optional.empty();
            }
        }
    }

    private static Alert existingDialog(Node owner, Workspace workspace) {
        boolean active = workspace.lifecycle() == WorkspaceLifecycle.ACTIVE;
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("目录已登记");
        dialog.setHeaderText("此目录已关联到“" + workspace.name() + "”");
        dialog.setContentText(workspace.root() + "\n\n"
                + (active ? "同一目录只需登记一次。可以打开已有工作区；修改名称请到“设置与管理 → 工作区”。" : "该工作区已归档，目录仍保留登记。请为新工作区选择其他目录。"));
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, CHOOSE_ANOTHER);
        if (active) {
            dialog.getDialogPane().getButtonTypes().add(OPEN);
        }
        PlatformDialogs.style(dialog, owner);
        return dialog;
    }

    private static void chooseExecution(Node owner, DesktopPresenter presenter, String name, Path directory) {
        ExecutionSelectionPanel selection = new ExecutionSelectionPanel(new SdkCoreSettingsGateway(presenter));
        Dialog<ExecutionOverrides> dialog = new Dialog<>();
        dialog.setTitle("创建工作区");
        dialog.setHeaderText("为“" + name + "”选择 Agent、模型与权限");
        dialog.getDialogPane().setContent(selection);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
        PlatformDialogs.style(dialog, owner);
        selection.prepareWorkspaceCreation();
        dialog.setResultConverter(button -> button == ButtonType.OK ? selection.execution() : null);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(ActionEvent.ACTION, event -> {
            if (selection.pending() || !selection.ready()) {
                event.consume();
            }
        });
        try {
            dialog.showAndWait().ifPresent(execution -> presenter.createWorkspace(name, directory, execution));
        } finally {
            selection.close();
        }
    }
}
