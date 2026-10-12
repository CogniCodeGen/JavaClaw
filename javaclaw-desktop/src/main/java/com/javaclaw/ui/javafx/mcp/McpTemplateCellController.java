package com.javaclaw.ui.javafx.mcp;

import com.javaclaw.application.mcp.McpManagementApplicationService.Template;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** MCP 模板 ListCell 的 FXML 内容 Controller。 */
public final class McpTemplateCellController {
    @FXML private Label nameLabel;
    @FXML private Label descriptionLabel;
    @FXML private Label toolsLabel;

    void apply(Template template) {
        nameLabel.setText(template.displayName());
        descriptionLabel.setText(template.description());
        toolsLabel.setText("工具：" + template.toolsHint());
    }
}
