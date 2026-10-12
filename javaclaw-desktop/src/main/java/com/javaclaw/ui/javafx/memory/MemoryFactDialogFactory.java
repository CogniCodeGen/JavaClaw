package com.javaclaw.ui.javafx.memory;

import com.javaclaw.app.UIHelper;
import com.javaclaw.application.memory.MemoryApplicationService.AddFactCommand;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 创建并立即释放新增事实 FXML 弹窗。 */
public final class MemoryFactDialogFactory {
    private static final URL VIEW = Objects.requireNonNull(
            MemoryFactDialogFactory.class.getResource("/fxml/memory/memory-fact-dialog.fxml"),
            "缺少 memory-fact-dialog.fxml");
    private static final ButtonType SAVE =
            new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
    private final SpringFxmlLoader loader;
    private final UIHelper ui;

    public MemoryFactDialogFactory(SpringFxmlLoader loader, UIHelper ui) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    Optional<AddFactCommand> show(Window owner, List<String> sections) {
        ViewHandle<VBox> handle = load();
        Node saveButton = null;
        try {
            MemoryFactDialogController controller =
                    handle.controller(MemoryFactDialogController.class);
            controller.configure(sections);
            Dialog<AddFactCommand> dialog = new Dialog<>();
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle("新增事实");
            dialog.setHeaderText("手动新增的事实将打上「用户保护位」，蒸馏不会静默覆盖。");
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, SAVE);
            saveButton = dialog.getDialogPane().lookupButton(SAVE);
            saveButton.disableProperty().bind(controller.invalidBinding());
            dialog.setResultConverter(button -> button == SAVE ? controller.command() : null);
            dialog.setOnShown(event -> controller.focusStatement());
            ui.styleDialog(dialog);
            return dialog.showAndWait();
        } finally {
            if (saveButton != null) saveButton.disableProperty().unbind();
            handle.close();
        }
    }

    private ViewHandle<VBox> load() {
        try {
            return loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载新增事实弹窗失败", failure);
        }
    }
}
