package com.javaclaw.ui.javafx.plugin.v2;

import com.javaclaw.application.plugin.v2.PluginManagementService.PluginDetails;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 权限草稿保留读取时的配置版本，状态刷新不能覆盖未提交的勾选。 */
final class PluginPermissionEditor implements AutoCloseable {
    private final VBox root;
    private final ReadOnlyBooleanProperty busy;
    private final Consumer<String> revoke;
    private final Map<String, CheckBox> choices = new LinkedHashMap<>();
    private final ReadOnlyBooleanWrapper dirty = new ReadOnlyBooleanWrapper();
    private Set<String> baseline = Set.of();
    private String plugin;
    private long revision;

    PluginPermissionEditor(VBox root, ReadOnlyBooleanProperty busy, Consumer<String> revoke) {
        this.root = root; this.busy = busy; this.revoke = revoke;
    }
    void apply(PluginDetails details, boolean replace) {
        if (!replace && details.summary().id().equals(plugin) && dirty.get()) return;
        plugin = details.summary().id(); revision = details.configuration().revision();
        baseline = Set.copyOf(details.configuration().grantedPermissions());
        choices.clear(); root.getChildren().clear();
        details.requiredPermissions().stream().sorted().forEach(permission -> {
            CheckBox box = new CheckBox(permission); box.setSelected(baseline.contains(permission));
            box.selectedProperty().addListener((ignored, before, after) -> dirty.set(!grantedPermissions().equals(baseline)));
            choices.put(permission, box); root.getChildren().add(box);
        });
        if (choices.isEmpty()) root.getChildren().add(new Label("插件未申请宿主权限"));
        Button button = new Button("立即撤销全部权限"); button.getStyleClass().addAll("jc-btn", "jc-btn-danger");
        button.setOnAction(event -> revoke.accept(plugin)); button.disableProperty().bind(busy);
        root.getChildren().add(button); dirty.set(false);
    }
    Set<String> grantedPermissions() {
        Set<String> grants = new LinkedHashSet<>(baseline); grants.removeAll(choices.keySet());
        choices.forEach((permission, box) -> { if (box.isSelected()) grants.add(permission); });
        return Set.copyOf(grants);
    }
    long revision() { return revision; }
    void setChoicesDisabled(boolean disabled) { choices.values().forEach(box -> box.setDisable(disabled)); }
    boolean isDirty() { return dirty.get(); }
    ReadOnlyBooleanProperty dirtyProperty() { return dirty.getReadOnlyProperty(); }
    @Override public void close() { choices.clear(); root.getChildren().clear(); baseline = Set.of(); plugin = null; dirty.set(false); }
}
