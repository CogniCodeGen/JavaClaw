package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.desktop.component.PlatformStylesheets;

/** 模型与服务名称可搜索的菜单；目录刷新只更新展示，不替换已选精确引用。 */
final class ChatModelPicker extends Button {
    private final ContextMenu menu = new ContextMenu();
    private final TextField search = new TextField();
    private final ListView<Entry> choices = new ListView<>();
    private final Consumer<ProviderRef> selected;
    private final Runnable lockedAction;
    private List<ProviderEndpoint> catalog = List.of();
    private Optional<ProviderRef> value = Optional.empty();
    private boolean locked;

    ChatModelPicker(Consumer<ProviderRef> selected, Runnable add, Runnable manage, Runnable restore, Runnable lockedAction) {
        this.selected = selected;
        this.lockedAction = lockedAction;
        setId("chatModel");
        setAccessibleText("选择聊天模型");
        getStyleClass().add("composer-select");
        search.setPromptText("搜索模型或服务…");
        search.setAccessibleText("搜索已配置模型");
        choices.setPrefHeight(200);
        choices.setPlaceholder(new Label("没有匹配的模型，可添加模型"));
        choices.setCellFactory(ignored -> new ModelCell());
        choices.setOnMouseClicked(event -> choose());
        choices.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                choose();
            }
        });
        search.textProperty().addListener((ignored, before, after) -> filter());
        VBox content = new VBox(8, search, choices, action("＋ 添加模型", add), action("管理模型与连接", manage),
                action("恢复项目设置", restore));
        content.setPrefWidth(340);
        content.setMaxWidth(340);
        PlatformStylesheets.applyTo(content);
        menu.getItems().add(new CustomMenuItem(content, false));
        setOnAction(event -> open());
        render(List.of(), Optional.empty(), false);
    }

    void render(List<ProviderEndpoint> providers, Optional<ProviderRef> selected, boolean locked) {
        catalog = providers;
        value = selected;
        this.locked = locked;
        setText(selected.map(this::name).orElse("选择模型") + (locked ? " · Agent 固定" : " ▾"));
        filter();
    }

    String name(ProviderRef reference) {
        return catalog.stream().filter(endpoint -> endpoint.id().equals(reference.endpointId()))
                .flatMap(endpoint -> endpoint.spec().models().stream())
                .filter(model -> model.modelId().equals(reference.model())).findFirst()
                .map(model -> model.displayName()).orElse(reference.model());
    }

    void close() {
        menu.hide();
    }

    private void open() {
        if (locked) {
            lockedAction.run();
        } else {
            menu.show(this, Side.TOP, 0, 0);
            search.requestFocus();
        }
    }

    private Button action(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("sidebar-manage-btn");
        button.setOnAction(event -> {
            menu.hide();
            action.run();
        });
        return button;
    }

    private void filter() {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        List<Entry> entries = catalog.stream().flatMap(endpoint -> endpoint.spec().models().stream()
                .filter(model -> model.supports(ProviderModelPurpose.CHAT))
                .map(model -> new Entry(new ProviderRef(endpoint.id(), endpoint.revision(), model.modelId()),
                        model.displayName(), endpoint.spec().displayName(), endpoint.lifecycle())))
                .filter(entry -> (entry.name() + " " + entry.service() + " " + entry.reference().model())
                        .toLowerCase(Locale.ROOT).contains(query)).toList();
        choices.getItems().setAll(entries);
    }

    private void choose() {
        Entry entry = choices.getSelectionModel().getSelectedItem();
        if (entry != null && entry.lifecycle() == ProviderLifecycle.ACTIVE) {
            menu.hide();
            selected.accept(entry.reference());
        }
    }

    private final class ModelCell extends ListCell<Entry> {
        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setText(null);
                setDisable(false);
                return;
            }
            boolean active = entry.lifecycle() == ProviderLifecycle.ACTIVE;
            setText((value.filter(entry.reference()::equals).isPresent() ? "✓ " : "") + entry.name() + " · "
                    + entry.service() + (active ? "" : " · " + SettingsLabels.providerLifecycle(entry.lifecycle())));
            setDisable(!active);
        }
    }

    private record Entry(ProviderRef reference, String name, String service, ProviderLifecycle lifecycle) {}
}
