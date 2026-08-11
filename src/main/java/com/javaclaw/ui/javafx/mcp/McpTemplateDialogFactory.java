package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.app.UIHelper;
import com.javaclaw.application.mcp.McpManagementApplicationService.SaveCommand;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;

/** 创建 FXML MCP 模板选择弹窗。 */
public final class McpTemplateDialogFactory {
    private static final URL VIEW = Objects.requireNonNull(
            McpTemplateDialogFactory.class.getResource("/fxml/mcp/mcp-template-dialog.fxml"),
            "缺少 mcp-template-dialog.fxml");
    private final SpringFxmlLoader loader;
    private final UIHelper ui;

    public McpTemplateDialogFactory(SpringFxmlLoader loader, UIHelper ui) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    Optional<SaveCommand> show(Window owner) {
        ViewHandle<VBox> handle;
        try { handle = loader.load(VIEW); }
        catch (IOException failure) { throw new UncheckedIOException("加载 MCP 模板弹窗失败", failure); }
        try {
            McpTemplateDialogController controller = handle.controller(McpTemplateDialogController.class);
            Dialog<SaveCommand> dialog = new Dialog<>();
            if (owner != null) dialog.initOwner(owner);
            dialog.setTitle("从模板添加 MCP 服务器");
            dialog.setHeaderText(null);
            dialog.getDialogPane().setContent(handle.root());
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
            controller.onAccept(() -> {
                dialog.setResult(controller.selectedCommand());
                dialog.close();
            });
            dialog.setResultConverter(button -> button == ButtonType.OK
                    ? controller.selectedCommand() : null);
            ui.styleDialog(dialog);
            return dialog.showAndWait();
        } finally { handle.close(); }
    }
}
