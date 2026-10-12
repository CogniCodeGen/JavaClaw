package com.javaclaw.ui.javafx.settings;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 设置窗口左侧导航 Controller；布局节点全部由 FXML 定义。 */
public final class SettingsNavigationController implements AutoCloseable {

    @FXML private VBox categoryList;
    @FXML private TextField searchField;
    @FXML private Button searchClear;

    private final SettingsNavigationViewModel viewModel = new SettingsNavigationViewModel();
    private final Map<SettingsCategory, Entry> entries = new EnumMap<>(SettingsCategory.class);
    private final Map<SettingsCategory.Group, Group> groups =
            new EnumMap<>(SettingsCategory.Group.class);
    private Set<SettingsCategory> dirtyCategories = Set.of();
    private Consumer<SettingsCategory> onSelected = ignored -> { };

    @FXML
    private void initialize() {
        indexGroupsAndEntries();
        searchField.textProperty().bindBidirectional(viewModel.queryProperty());
        viewModel.queryProperty().addListener((ignored, previous, value) -> render());
        viewModel.selectedProperty().addListener((ignored, previous, selected) -> renderSelection());
        render();
        renderSelection();
    }

    public SettingsNavigationViewModel viewModel() {
        return viewModel;
    }

    public void configure(Consumer<SettingsCategory> selectionListener) {
        onSelected = Objects.requireNonNull(selectionListener, "selectionListener");
    }

    public void select(SettingsCategory category) {
        Objects.requireNonNull(category, "category");
        viewModel.select(category);
        render();
        onSelected.accept(category);
    }

    public void showDirty(Set<SettingsCategory> categories) {
        dirtyCategories = Set.copyOf(categories);
        renderDirtyMarks();
    }

    @FXML
    private void categorySelected(ActionEvent event) {
        ToggleButton button = (ToggleButton) event.getSource();
        SettingsCategory category = SettingsCategory.valueOf(String.valueOf(button.getUserData()));
        if (button.isSelected()) select(category);
        else button.setSelected(true);
    }

    @FXML
    private void groupClicked(MouseEvent event) {
        if (!viewModel.query().isEmpty()) return;
        Label header = (Label) event.getSource();
        SettingsCategory.Group group = SettingsCategory.Group.valueOf(
                String.valueOf(header.getUserData()));
        viewModel.toggle(group);
        render();
    }

    @FXML
    private void clearSearch() {
        searchField.clear();
        searchField.requestFocus();
    }

    @FXML
    private void searchKeyPressed(KeyEvent event) {
        if (event.getCode() == KeyCode.ENTER && !viewModel.query().isEmpty()) {
            entries.keySet().stream().filter(category -> category.matches(viewModel.query()))
                    .findFirst().ifPresent(this::select);
        } else if (event.getCode() == KeyCode.ESCAPE && !viewModel.query().isEmpty()) {
            searchField.clear();
            event.consume();
        }
    }

    private void indexGroupsAndEntries() {
        for (Node node : categoryList.getChildren()) {
            if (node instanceof Label header
                    && header.getStyleClass().contains("settings-nav-group")) {
                SettingsCategory.Group id = SettingsCategory.Group.valueOf(
                        String.valueOf(header.getUserData()));
                groups.put(id, new Group(header, null, (Region) header.getGraphic(), List.of()));
            } else if (node instanceof VBox children
                    && children.getStyleClass().contains("modal-nav-children")) {
                SettingsCategory.Group id = SettingsCategory.Group.valueOf(
                        String.valueOf(children.getUserData()));
                List<Entry> groupEntries = new ArrayList<>();
                for (Node child : children.getChildren()) {
                    Entry entry = entry((ToggleButton) child);
                    entries.put(entry.category(), entry);
                    groupEntries.add(entry);
                }
                Group indexed = Objects.requireNonNull(groups.get(id), "缺少导航分组标题: " + id);
                groups.put(id, new Group(indexed.header(), children,
                        indexed.dirtyDot(), List.copyOf(groupEntries)));
            }
        }
        if (entries.size() != SettingsCategory.values().length) {
            throw new IllegalStateException("设置导航分类不完整: " + entries.keySet());
        }
    }

    private static Entry entry(ToggleButton button) {
        SettingsCategory category = SettingsCategory.valueOf(String.valueOf(button.getUserData()));
        HBox content = (HBox) button.getGraphic();
        Label before = (Label) content.getChildren().get(0);
        Label match = (Label) content.getChildren().get(1);
        Label after = (Label) content.getChildren().get(2);
        Region dirty = (Region) content.getChildren().get(4);
        content.prefWidthProperty().bind(button.widthProperty().subtract(26));
        return new Entry(category, button, before, match, after, dirty);
    }

    private void render() {
        String query = viewModel.query();
        boolean searching = !query.isEmpty();
        searchClear.setVisible(searching);
        searchClear.setManaged(searching);
        for (Group group : groups.values()) {
            int matches = 0;
            for (Entry entry : group.entries()) {
                boolean match = entry.category().matches(query);
                if (match) matches++;
                visible(entry.button(), !searching || match);
                highlight(entry, searching && match ? query : "");
            }
            boolean expanded = searching ? matches > 0 : viewModel.expanded(group.id());
            visible(group.children(), expanded);
            visible(group.header(), !searching || matches > 0);
            group.header().setText((expanded ? "▾  " : "▸  ") + group.id().displayName());
        }
        renderDirtyMarks();
    }

    private void renderSelection() {
        SettingsCategory selected = viewModel.selected();
        entries.forEach((category, entry) -> entry.button().setSelected(category == selected));
    }

    private void highlight(Entry entry, String query) {
        String name = entry.category().displayName();
        int index = query.isEmpty() ? -1
                : name.toLowerCase(java.util.Locale.ROOT).indexOf(query);
        entry.before().setText(index < 0 ? name : name.substring(0, index));
        entry.match().setText(index < 0 ? "" : name.substring(index, index + query.length()));
        entry.after().setText(index < 0 ? "" : name.substring(index + query.length()));
        visible(entry.match(), index >= 0);
        visible(entry.after(), index >= 0 && !entry.after().getText().isEmpty());
    }

    private void renderDirtyMarks() {
        for (Group group : groups.values()) {
            boolean anyDirty = false;
            for (Entry entry : group.entries()) {
                boolean dirty = dirtyCategories.contains(entry.category());
                anyDirty |= dirty;
                visible(entry.dirtyDot(), dirty);
            }
            visible(group.dirtyDot(), !group.children().isVisible() && anyDirty);
        }
    }

    private static void visible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    @Override
    public void close() {
        searchField.textProperty().unbindBidirectional(viewModel.queryProperty());
        entries.values().forEach(entry -> entry.content().prefWidthProperty().unbind());
        onSelected = ignored -> { };
    }

    private record Entry(SettingsCategory category, ToggleButton button, Label before,
                         Label match, Label after, Region dirtyDot) {
        HBox content() {
            return (HBox) button.getGraphic();
        }
    }

    private record Group(Label header, VBox children, Region dirtyDot, List<Entry> entries) {
        SettingsCategory.Group id() {
            return SettingsCategory.Group.valueOf(String.valueOf(header.getUserData()));
        }
    }
}
