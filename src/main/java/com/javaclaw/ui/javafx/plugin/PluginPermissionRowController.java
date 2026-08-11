package com.javaclaw.ui.javafx.plugin;

import com.javaclaw.application.plugin.PluginManagementApplicationService.Permission;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

/** 权限行 FXML Controller。 */
public final class PluginPermissionRowController {

    @FXML private Label permissionLabel;

    void configure(Permission permission) {
        permissionLabel.setText(permission.name() + (permission.granted() ? "（已授权）" : ""));
    }
}
