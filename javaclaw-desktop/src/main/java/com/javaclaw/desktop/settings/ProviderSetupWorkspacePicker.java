package com.javaclaw.desktop.settings;

import java.io.File;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import com.javaclaw.api.Workspace;
import com.javaclaw.api.WorkspaceLifecycle;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 无目标工作区时在当前流程内选择或创建工作区，不丢失已经保存的模型。 */
final class ProviderSetupWorkspacePicker extends VBox {
    private final CoreSettingsGateway gateway;
    private final Window owner;
    private final ProviderSetupTarget original;
    private final ComboBox<Workspace> choices = new ComboBox<>();
    private final Label status = new Label();
    private Consumer<ProviderSetupTarget> listener = ignored -> {};
    private boolean pending;

    ProviderSetupWorkspacePicker(Window owner, CoreSettingsGateway gateway, ProviderSetupTarget original) {
        super(8);
        this.owner = owner;
        this.gateway = gateway;
        this.original = original;
        PlatformComponentFactory components = new PlatformComponentFactory();
        choices.setPromptText("选择工作区");
        choices.setId("providerWizardWorkspace");
        choices.setMaxWidth(Double.MAX_VALUE);
        choices.setConverter(SettingsLabels.converter(Workspace::name));
        choices.valueProperty().addListener((ignored, before, value) -> listener.accept(target()));
        Button create = components.action("创建工作区", ActionStyle.SOFT, ActionSize.COMPACT);
        create.setOnAction(event -> createWorkspace());
        Button reload = components.action("刷新工作区", ActionStyle.GHOST, ActionSize.COMPACT);
        reload.setOnAction(event -> reload());
        HBox actions = new HBox(8, choices, create, reload);
        HBox.setHgrow(choices, Priority.ALWAYS);
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
        getChildren().addAll(new Label("选择在哪里使用此模型"), actions, status);
        boolean missing = original.workspaceId().isEmpty();
        setVisible(missing);
        setManaged(missing);
        if (missing) {
            reload();
        }
    }

    ProviderSetupTarget target() {
        Workspace workspace = choices.getValue();
        return original.workspaceId().isPresent() || workspace == null
                ? original
                : new ProviderSetupTarget(Optional.of(workspace.id()), Optional.empty(), workspace.name());
    }

    void onTargetChanged(Consumer<ProviderSetupTarget> value) {
        listener = value;
    }

    boolean pending() {
        return pending;
    }

    private void reload() {
        if (pending) {
            return;
        }
        pending = true;
        setDisable(true);
        status.setText("正在读取工作区…");
        gateway.workspaces().whenComplete((workspaces, failure) -> {
            pending = false;
            setDisable(false);
            if (failure != null) {
                status.setText("读取失败：" + SettingsFailures.message(failure));
                return;
            }
            Workspace selected = choices.getValue();
            choices.getItems()
                    .setAll(workspaces.stream()
                            .filter(workspace -> workspace.lifecycle() == WorkspaceLifecycle.ACTIVE)
                            .toList());
            choices.setValue(
                    selected == null
                            ? null
                            : choices.getItems().stream()
                                    .filter(workspace -> workspace.id().equals(selected.id()))
                                    .findFirst()
                                    .orElse(null));
            status.setText("选择工作区后继续；也可以选择文件夹创建工作区，已填模型会保留。");
        });
    }

    private void createWorkspace() {
        if (pending) {
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择工作区文件夹");
        File directory = chooser.showDialog(owner);
        if (directory == null) {
            return;
        }
        pending = true;
        setDisable(true);
        status.setText("正在创建工作区…");
        gateway.createModelWorkspace(directory.getName(), directory.toPath()).whenComplete((workspace, failure) -> {
            pending = false;
            setDisable(false);
            if (failure != null) {
                status.setText("创建工作区失败：" + SettingsFailures.message(failure));
                return;
            }
            choices.getItems().add(workspace);
            choices.setValue(workspace);
            status.setText("工作区已创建，可以继续使用模型。");
        });
    }
}
