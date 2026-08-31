package com.javaclaw.desktop;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Dialog;
import javafx.scene.control.TextInputDialog;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

/** JavaFX 宿主交互实现；所有对话框继承当前窗口主题，文件路径不会写入协议。 */
final class JavaFxDesktopDialogGateway implements DesktopDialogGateway {
    private Consumer<URI> externalBrowser = ignored -> {
        throw new IllegalStateException("当前启动器未提供外部浏览器打开能力");
    };

    @Override
    public Optional<Path> chooseDirectory(Node owner, String title) {
        var chooser = new DirectoryChooser();
        chooser.setTitle(title);
        var selected = chooser.showDialog(owner.getScene().getWindow());
        return selected == null ? Optional.empty() : Optional.of(selected.toPath());
    }

    @Override
    public Optional<Path> chooseOpenFile(Node owner, String title, List<FileType> types) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        addFilters(chooser, types);
        var selected = chooser.showOpenDialog(owner.getScene().getWindow());
        return selected == null ? Optional.empty() : Optional.of(selected.toPath());
    }

    @Override
    public List<Path> chooseOpenFiles(Node owner, String title, List<FileType> types) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        addFilters(chooser, types);
        var selected = chooser.showOpenMultipleDialog(owner.getScene().getWindow());
        return selected == null
                ? List.of()
                : selected.stream().map(java.io.File::toPath).toList();
    }

    @Override
    public Optional<Path> chooseSaveFile(Node owner, String title, String suggestedName, List<FileType> types) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialFileName(suggestedName == null ? "" : suggestedName);
        addFilters(chooser, types);
        var selected = chooser.showSaveDialog(owner.getScene().getWindow());
        return selected == null ? Optional.empty() : Optional.of(selected.toPath());
    }

    @Override
    public boolean confirm(Node owner, String title, String header, String message, String acceptText) {
        var accept = new ButtonType(acceptText, ButtonBar.ButtonData.OK_DONE);
        var dialog = new Alert(Alert.AlertType.CONFIRMATION, message, accept, ButtonType.CANCEL);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        style(owner, dialog);
        styleButton(dialog, accept, dangerous(acceptText) ? UiActionKind.DANGER : UiActionKind.PRIMARY);
        styleButton(dialog, ButtonType.CANCEL, UiActionKind.GHOST);
        return dialog.showAndWait().orElse(ButtonType.CANCEL) == accept;
    }

    @Override
    public Optional<String> promptText(Node owner, String title, String header, String initialValue) {
        var dialog = new TextInputDialog(initialValue == null ? "" : initialValue);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        style(owner, dialog);
        styleButton(dialog, ButtonType.OK, UiActionKind.PRIMARY);
        styleButton(dialog, ButtonType.CANCEL, UiActionKind.GHOST);
        return dialog.showAndWait();
    }

    @Override
    public Optional<String> choose(Node owner, String title, String header, List<String> choices) {
        if (choices == null || choices.isEmpty()) {
            return Optional.empty();
        }
        var dialog = new ChoiceDialog<>(choices.getFirst(), choices);
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        style(owner, dialog);
        styleButton(dialog, ButtonType.OK, UiActionKind.PRIMARY);
        styleButton(dialog, ButtonType.CANCEL, UiActionKind.GHOST);
        return dialog.showAndWait();
    }

    @Override
    public UnsavedDecision resolveUnsavedChanges(Node owner, String resource) {
        var save = new ButtonType("保存并继续", ButtonBar.ButtonData.YES);
        var discard = new ButtonType("放弃修改", ButtonBar.ButtonData.NO);
        var dialog = new Alert(Alert.AlertType.CONFIRMATION, "未保存的内容不会自动写入服务端。", save, discard, ButtonType.CANCEL);
        dialog.setTitle("有未保存的修改");
        dialog.setHeaderText("“" + (resource == null || resource.isBlank() ? "当前内容" : resource) + "”尚未保存");
        style(owner, dialog);
        styleButton(dialog, save, UiActionKind.PRIMARY);
        styleButton(dialog, discard, UiActionKind.DANGER);
        styleButton(dialog, ButtonType.CANCEL, UiActionKind.GHOST);
        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result == save) {
            return UnsavedDecision.SAVE;
        }
        if (result == discard) {
            return UnsavedDecision.DISCARD;
        }
        return UnsavedDecision.CANCEL;
    }

    @Override
    public void openExternal(URI uri) {
        externalBrowser.accept(Objects.requireNonNull(uri, "uri"));
    }

    /** 安装 JavaFX HostServices 提供的 HTTPS 打开器；只能在组件图完成装配后调用。 */
    void setExternalBrowser(Consumer<URI> opener) {
        externalBrowser = Objects.requireNonNull(opener, "opener");
    }

    private static void addFilters(FileChooser chooser, List<FileType> types) {
        if (types == null) {
            return;
        }
        for (FileType type : types) {
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(type.description(), type.patterns()));
        }
    }

    private static void style(Node owner, Dialog<?> dialog) {
        dialog.initOwner(owner.getScene().getWindow());
        dialog.getDialogPane().setGraphic(null);
        String theme = owner.getScene().getRoot().getStyleClass().stream()
                .filter(value -> value.startsWith("theme-"))
                .map(value -> value.substring(6))
                .findFirst()
                .orElse(DesktopTheme.DEFAULT_ID);
        DesktopTheme.apply(dialog.getDialogPane(), theme);
    }

    private static void styleButton(Dialog<?> dialog, ButtonType type, UiActionKind kind) {
        if (dialog.getDialogPane().lookupButton(type) instanceof Button button) {
            button.setId("dialog-" + type.getButtonData().name().toLowerCase(java.util.Locale.ROOT));
            button.setAccessibleText(type.getText());
            kind.apply(button);
        }
    }

    static boolean dangerous(String value) {
        String text = value == null ? "" : value;
        return List.of("删除", "清除", "清理", "覆盖", "替换", "撤销", "卸载", "移除", "中断", "拒绝").stream()
                .anyMatch(text::contains);
    }
}
