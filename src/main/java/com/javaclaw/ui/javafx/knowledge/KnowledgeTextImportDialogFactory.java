package com.javaclaw.ui.javafx.knowledge;

import com.javaclaw.app.UIHelper;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;

/** Creates and releases the FXML-backed text import dialog. */
public final class KnowledgeTextImportDialogFactory {
    private static final URL VIEW = Objects.requireNonNull(
            KnowledgeTextImportDialogFactory.class.getResource(
                    "/fxml/knowledge/knowledge-text-import-dialog.fxml"),
            "缺少 knowledge-text-import-dialog.fxml");
    private final SpringFxmlLoader loader;
    private final FxDispatcher fx;
    private final UIHelper ui;

    public KnowledgeTextImportDialogFactory(
            SpringFxmlLoader loader, FxDispatcher fx, UIHelper ui) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    Optional<Draft> show(Window owner) {
        try (ViewHandle<VBox> handle = loader.load(VIEW)) {
            KnowledgeTextImportDialogController controller =
                    handle.controller(KnowledgeTextImportDialogController.class);
            Dialog<ButtonType> dialog = new Dialog<>();
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle("粘贴文本导入");
            dialog.setHeaderText("把文本作为一个知识库文档导入");
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            ui.styleDialog(dialog);
            dialog.setOnShown(event -> fx.dispatchLater(controller::requestFocus));
            if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return Optional.empty();
            }
            return Optional.of(controller.draft());
        } catch (IOException failure) {
            throw new UncheckedIOException("加载文本导入弹窗失败", failure);
        }
    }

    record Draft(String title, String text) { }
}
