package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.app.UIHelper;
import com.javaclaw.application.mcp.McpManagementApplicationService.SaveCommand;
import com.javaclaw.application.mcp.McpManagementApplicationService.Server;
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

/** 创建 FXML MCP 编辑弹窗，并在关闭时清除密钥字段与子 Controller。 */
public final class McpServerEditorFactory {
    private static final URL VIEW = Objects.requireNonNull(
            McpServerEditorFactory.class.getResource("/fxml/mcp/mcp-server-editor.fxml"),
            "缺少 mcp-server-editor.fxml");
    private final SpringFxmlLoader loader;

    public McpServerEditorFactory(SpringFxmlLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    Optional<SaveCommand> show(Window owner, Server existing) {
        return showLoaded(owner, existing, null);
    }

    Optional<SaveCommand> showSeed(Window owner, SaveCommand seed) {
        return showLoaded(owner, null, seed);
    }

    private Optional<SaveCommand> showLoaded(Window owner, Server existing, SaveCommand seed) {
        ViewHandle<VBox> handle;
        try {
            handle = loader.load(VIEW);
        } catch (IOException failure) {
            throw new UncheckedIOException("加载 MCP 服务器编辑器失败", failure);
        }
        try {
            McpServerEditorController controller =
                    handle.controller(McpServerEditorController.class);
            if (seed == null) controller.configure(existing); else controller.configure(seed);
            Dialog<SaveCommand> dialog = new Dialog<>();
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle(existing == null ? "添加 MCP 服务器" : "编辑 MCP 服务器");
            dialog.setHeaderText(null);
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
            dialog.getDialogPane().setPrefWidth(580);
            Button save = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            save.setText(existing == null ? "添加" : "更新");
            save.getStyleClass().addAll("jc-btn", "jc-btn-primary");
            save.addEventFilter(ActionEvent.ACTION, event -> {
                if (!controller.validate()) event.consume();
            });
            dialog.setResultConverter(button -> button == ButtonType.OK ? controller.command() : null);
            UIHelper.styleDialog(dialog);
            addStylesheet(dialog);
            return dialog.showAndWait();
        } finally {
            handle.close();
        }
    }

    private static void addStylesheet(Dialog<?> dialog) {
        URL css = McpServerEditorFactory.class.getResource("/css/mcp-settings.css");
        if (css != null) dialog.getDialogPane().getStylesheets().add(css.toExternalForm());
    }
}
