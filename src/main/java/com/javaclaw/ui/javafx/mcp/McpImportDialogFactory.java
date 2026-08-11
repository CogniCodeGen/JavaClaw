package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.app.UIHelper;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;

/** 创建 FXML MCP JSON 导入弹窗。 */
public final class McpImportDialogFactory {
    private static final URL VIEW = Objects.requireNonNull(
            McpImportDialogFactory.class.getResource("/fxml/mcp/mcp-import-dialog.fxml"),
            "缺少 mcp-import-dialog.fxml");
    private final SpringFxmlLoader loader;

    public McpImportDialogFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    Optional<ImportCommand> show(Window owner) {
        ViewHandle<VBox> handle;
        try { handle = loader.load(VIEW); }
        catch (IOException failure) { throw new UncheckedIOException("加载 MCP 导入弹窗失败", failure); }
        try {
            McpImportDialogController controller = handle.controller(McpImportDialogController.class);
            Dialog<ImportCommand> dialog = new Dialog<>();
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle("粘贴 JSON 导入 MCP 服务器");
            dialog.setHeaderText(null);
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
            dialog.getDialogPane().setPrefWidth(580);
            Button importButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            importButton.setText("导入");
            importButton.getStyleClass().addAll("jc-btn", "jc-btn-primary");
            importButton.addEventFilter(ActionEvent.ACTION, event -> {
                if (!controller.validate()) event.consume();
            });
            dialog.setResultConverter(button -> button == ButtonType.OK
                    ? new ImportCommand(controller.json(), controller.fallbackName()) : null);
            UIHelper.styleDialog(dialog);
            return dialog.showAndWait();
        } finally { handle.close(); }
    }

    record ImportCommand(String json, String fallbackName) { }
}
