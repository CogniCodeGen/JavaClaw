package com.javaclaw.desktop.settings;

import java.util.Objects;
import java.util.function.Consumer;

import javafx.collections.FXCollections;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import com.javaclaw.api.Workspace;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 面包屑下方的 Workspace 作用域选择控件；所有状态由 Presenter 投影。 */
final class ManagementScopeControl extends HBox {
    private final ComboBox<Workspace> workspaces = new ComboBox<>();
    private final Label status = new Label();
    private final Button reload;
    private boolean rendering;

    ManagementScopeControl(Consumer<Workspace> selection, Runnable reloadAction) {
        PlatformComponentFactory components = new PlatformComponentFactory();
        Label label = new Label("工作区");
        label.getStyleClass().add("management-scope-label");
        workspaces.setPromptText("请选择工作区");
        workspaces.setAccessibleText("设置中心固定工作区");
        workspaces.setCellFactory(ignored -> components.detailCell(
                Workspace::name, workspace -> workspace.id().value() + " · 版本 " + workspace.revision()));
        workspaces.setButtonCell(components.textCell(Workspace::name));
        workspaces.valueProperty().addListener((ignored, previous, selected) -> {
            if (!rendering && selected != null) {
                Objects.requireNonNull(selection, "selection").accept(selected);
            }
        });
        reload = components.action("重新读取", ActionStyle.GHOST, ActionSize.COMPACT);
        reload.setOnAction(
                event -> Objects.requireNonNull(reloadAction, "reloadAction").run());
        status.getStyleClass().add("management-scope-status");
        getChildren().addAll(label, workspaces, reload, status);
        getStyleClass().add("management-scope");
    }

    void render(ManagementScopeState state) {
        rendering = true;
        try {
            workspaces.setItems(FXCollections.observableArrayList(state.workspaces()));
            workspaces.setValue(state.selected().orElse(null));
            workspaces.setDisable(state.phase() != SettingsLoadState.READY
                    || state.workspaces().isEmpty());
            reload.setDisable(state.loading());
            status.setText(state.message());
            status.setVisible(!state.message().isBlank());
            status.setManaged(!state.message().isBlank());
        } finally {
            rendering = false;
        }
    }
}
