package com.javaclaw.ui.javafx.plugin.v2;

import com.javaclaw.application.plugin.v2.PluginManagementService;
import com.javaclaw.application.plugin.v2.PluginManagementService.PluginDetails;
import com.javaclaw.application.plugin.v2.PluginManagementService.PluginVersion;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 展示全局已安装工件，并显式切换当前工作区的版本绑定。 */
final class PluginVersionSelector implements AutoCloseable {
    private final HBox root = new HBox(8);
    private final ComboBox<PluginVersion> versions = new ComboBox<>();
    private final Button select = new Button("应用此版本");
    private final UiAsyncAction<List<PluginVersion>> refresh;
    private final PluginManagementService service;
    private final String workspace;
    private final PluginCenterOperations operations;
    private final BooleanSupplier dirty;
    private final Consumer<Throwable> failure;
    private PluginDetails current;
    private boolean closed;

    PluginVersionSelector(PluginManagementService service, String workspace, PluginCenterOperations operations,
                          ManagedTaskExecutor tasks, FxDispatcher fx, BooleanSupplier dirty, Consumer<Throwable> failure) {
        this.service = service; this.workspace = workspace; this.operations = operations; this.dirty = dirty; this.failure = failure;
        refresh = new UiAsyncAction<>(tasks, fx);
        versions.setId("installedPluginVersions"); select.setId("selectPluginVersionButton");
        versions.setConverter(new StringConverter<>() {
            @Override public String toString(PluginVersion value) { return value == null ? "" : value.version() + (value.selected() ? "（当前工作区）" : ""); }
            @Override public PluginVersion fromString(String value) { throw new UnsupportedOperationException(); }
        });
        versions.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(versions, javafx.scene.layout.Priority.ALWAYS);
        root.getChildren().setAll(new Label("已安装版本"), versions, select);
        select.setOnAction(event -> {
            PluginDetails details = current; PluginVersion target = versions.getValue();
            if (details != null && target != null) operations.leave(dirty.getAsBoolean(),
                    () -> operations.selectVersion(details, target), () -> { });
        });
        select.disableProperty().bind(operations.busyProperty().or(refresh.busyProperty()));
        versions.disableProperty().bind(select.disableProperty());
    }
    Node root() { return root; }
    void update(PluginDetails details) {
        if (closed) return;
        boolean changed = current == null || !current.summary().id().equals(details.summary().id());
        current = details;
        String selectedHash = changed || versions.getValue() == null ? details.summary().artifactHash() : versions.getValue().artifactHash();
        String id = details.summary().id();
        refresh.execute(TaskSpec.io("插件已安装版本"), task -> service.versions(workspace, id), values -> {
            if (current == null || !current.summary().id().equals(id)) return;
            versions.getItems().setAll(values);
            values.stream().filter(value -> value.artifactHash().equals(selectedHash)).findFirst()
                    .or(() -> values.stream().filter(PluginVersion::selected).findFirst()).ifPresent(versions::setValue);
        }, failure);
    }
    @Override public void close() { closed = true; current = null; refresh.close(); root.getChildren().clear(); }
}
