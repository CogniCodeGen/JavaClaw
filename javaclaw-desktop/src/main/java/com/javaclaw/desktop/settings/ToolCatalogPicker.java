package com.javaclaw.desktop.settings;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import javafx.collections.ListChangeListener;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ToolDescriptor;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 设置页面复用的工具目录搜索和精确选择控件。 */
public final class ToolCatalogPicker extends VBox {
    /** 工具选择数量约束。 */
    public enum Mode {
        /** 只允许选择一个工具。 */
        SINGLE,
        /** 允许选择多个精确工具名。 */
        MULTIPLE
    }

    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final TextField query = new TextField();
    private final Button search;
    private final Button clear;
    private final ListView<ToolDescriptor> list = new ListView<>();
    private final Label status = new Label();
    private Consumer<String> searchListener = ignored -> {};
    private Consumer<Set<String>> selectionListener = ignored -> {};
    private final LinkedHashMap<String, ToolDescriptor> knownTools = new LinkedHashMap<>();
    private final LinkedHashSet<String> selectedNames = new LinkedHashSet<>();
    private String bindingKey = "";
    private boolean rendering;

    /**
     * 创建目录选择器。
     *
     * @param mode 单选或多选
     */
    public ToolCatalogPicker(Mode mode) {
        Objects.requireNonNull(mode, "mode");
        search = components.action("搜索", ActionStyle.SOFT, ActionSize.COMPACT);
        clear = components.action("清除选择", ActionStyle.GHOST, ActionSize.COMPACT);
        configure(mode);
    }

    /** @param listener 查询提交回调 */
    public void onSearch(Consumer<String> listener) {
        searchListener = Objects.requireNonNull(listener, "listener");
    }

    /** @param listener 精确工具名集合选择回调 */
    public void onSelection(Consumer<Set<String>> listener) {
        selectionListener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * 渲染权威目录状态并恢复已选工具名。
     *
     * @param state 不可变目录状态
     * @param selectedNames 草稿中的精确工具名
     */
    public void render(ToolCatalogSelectionState state, Set<String> selectedNames) {
        ToolCatalogSelectionState checked = Objects.requireNonNull(state, "state");
        Set<String> selected = Set.copyOf(Objects.requireNonNull(selectedNames, "selectedNames"));
        rendering = true;
        try {
            String currentBinding = bindingKey(checked);
            if (!bindingKey.equals(currentBinding)) {
                knownTools.clear();
                bindingKey = currentBinding;
            }
            checked.result()
                    .ifPresent(result -> result.tools()
                            .forEach(tool -> knownTools.put(tool.identity().name(), tool)));
            this.selectedNames.clear();
            this.selectedNames.addAll(selected);
            query.setText(checked.query());
            list.getItems()
                    .setAll(checked.result().map(result -> result.tools()).orElse(List.of()));
            list.getSelectionModel().clearSelection();
            for (int index = 0; index < list.getItems().size(); index++) {
                if (selected.contains(list.getItems().get(index).identity().name())) {
                    list.getSelectionModel().select(index);
                }
            }
            status.setText(message(checked, selected));
            boolean pending = checked.phase() == SettingsLoadState.LOADING;
            list.setDisable(!checked.ready());
            search.setDisable(pending || checked.workspaceId().isEmpty());
            clear.setDisable(pending || selected.isEmpty());
        } finally {
            rendering = false;
        }
    }

    /** @return 单选模式当前选中的精确描述 */
    public java.util.Optional<ToolDescriptor> selectedTool() {
        return selectedNames.stream().findFirst().map(knownTools::get);
    }

    /** @return 当前保留的精确工具名；搜索过滤不会丢弃其他已选项 */
    public Set<String> selectedNames() {
        return Set.copyOf(selectedNames);
    }

    private void configure(Mode mode) {
        setSpacing(8);
        query.setPromptText("按名称、说明或标签筛选");
        query.setId("toolCatalogQuery");
        list.setId("toolCatalogList");
        list.setPrefHeight(180);
        list.getSelectionModel()
                .setSelectionMode(mode == Mode.MULTIPLE ? SelectionMode.MULTIPLE : SelectionMode.SINGLE);
        list.setCellFactory(ignored -> components.detailCell(
                descriptor -> descriptor.identity().name(),
                descriptor -> descriptor.identity().producerId() + " · 版本 "
                        + descriptor.identity().revision() + " · " + SettingsLabels.toolRisk(descriptor.risk())));
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
        HBox searchRow = new HBox(8, query, search, clear);
        HBox.setHgrow(query, Priority.ALWAYS);
        getChildren().addAll(searchRow, list, status);
        search.setOnAction(event -> searchListener.accept(query.getText()));
        query.setOnAction(event -> searchListener.accept(query.getText()));
        clear.setOnAction(event -> {
            selectedNames.clear();
            list.getSelectionModel().clearSelection();
            selectionListener.accept(Set.of());
        });
        list.getSelectionModel().getSelectedItems().addListener((ListChangeListener<ToolDescriptor>)
                change -> notifySelection());
    }

    private void notifySelection() {
        if (!rendering) {
            Set<String> visible = list.getItems().stream()
                    .map(tool -> tool.identity().name())
                    .collect(java.util.stream.Collectors.toSet());
            selectedNames.removeAll(visible);
            list.getSelectionModel().getSelectedItems().stream()
                    .map(tool -> tool.identity().name())
                    .forEach(selectedNames::add);
            selectionListener.accept(Set.copyOf(selectedNames));
        }
    }

    private String message(ToolCatalogSelectionState state, Set<String> selected) {
        Set<String> available = state.result().stream()
                .flatMap(result -> result.tools().stream())
                .map(tool -> tool.identity().name())
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> unavailable = new LinkedHashSet<>(selected);
        unavailable.removeAll(available);
        if (!unavailable.isEmpty() && state.ready() && state.query().isBlank()) {
            return "以下已选工具当前不可用，保存前请清除选择：" + String.join("、", unavailable);
        }
        return state.message();
    }

    private static String bindingKey(ToolCatalogSelectionState state) {
        return state.workspaceId().map(Object::toString).orElse("") + "|"
                + state.permissionProfile().map(Object::toString).orElse("") + "|"
                + state.agentProfile().map(Object::toString).orElse("");
    }
}
