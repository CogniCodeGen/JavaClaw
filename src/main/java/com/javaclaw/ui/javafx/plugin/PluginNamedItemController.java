package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.plugin.PluginManagementApplicationService.NamedItem;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** 插件技能或工具条目的 FXML Controller。 */
public final class PluginNamedItemController {

    @FXML private Label nameLabel;
    @FXML private Label descriptionLabel;

    void configure(NamedItem item) {
        nameLabel.setText("• " + item.name());
        descriptionLabel.setText(item.description());
        descriptionLabel.setVisible(!item.description().isBlank());
        descriptionLabel.setManaged(!item.description().isBlank());
    }
}
