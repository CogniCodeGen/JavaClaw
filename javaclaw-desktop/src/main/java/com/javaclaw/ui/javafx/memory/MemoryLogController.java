package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.ChangeItem;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** append-only 记忆审计日志和操作筛选。 */
public final class MemoryLogController implements MemorySectionController, AutoCloseable {
    @FXML private Button allFilter;
    @FXML private Button addFilter;
    @FXML private Button updateFilter;
    @FXML private Button removeFilter;
    @FXML private Button mergeFilter;
    @FXML private VBox card;
    @FXML private VBox rows;
    @FXML private Label empty;
    private final MemoryComponentFactory components;
    private final List<MemoryChildView<?>> children = new ArrayList<>();
    private Snapshot snapshot;
    private String query = "";
    private String operation = "";

    public MemoryLogController(MemoryComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    @Override
    public void apply(Snapshot snapshot, String query) {
        this.snapshot = snapshot;
        this.query = query == null ? "" : query;
        render();
    }

    @FXML private void showAll() { filter(""); }
    @FXML private void showAdds() { filter("ADD"); }
    @FXML private void showUpdates() { filter("UPDATE"); }
    @FXML private void showRemoves() { filter("REMOVE"); }
    @FXML private void showMerges() { filter("MERGE"); }

    private void filter(String value) {
        operation = value;
        render();
    }

    private void render() {
        if (snapshot == null) return;
        closeChildren();
        refreshFilters();
        List<ChangeItem> matches = snapshot.changes().stream()
                .filter(item -> operation.isBlank()
                        || operation.equalsIgnoreCase(item.operation()))
                .filter(item -> MemoryUiText.matches(query,
                        item.detail(), item.type(), item.targetId())).toList();
        card.setVisible(!matches.isEmpty());
        card.setManaged(!matches.isEmpty());
        empty.setVisible(matches.isEmpty());
        empty.setManaged(matches.isEmpty());
        boolean first = true;
        for (ChangeItem item : matches) {
            MemoryChildView<javafx.scene.layout.HBox> child = components.change(item, first);
            first = false;
            children.add(child);
            rows.getChildren().add(child.root());
        }
    }

    private void refreshFilters() {
        select(allFilter, operation.isBlank());
        select(addFilter, "ADD".equals(operation));
        select(updateFilter, "UPDATE".equals(operation));
        select(removeFilter, "REMOVE".equals(operation));
        select(mergeFilter, "MERGE".equals(operation));
    }

    private static void select(Button button, boolean selected) {
        button.getStyleClass().remove("mc-filter-chip-on");
        if (selected) button.getStyleClass().add("mc-filter-chip-on");
    }

    private void closeChildren() {
        rows.getChildren().clear();
        children.forEach(MemoryChildView::close);
        children.clear();
    }

    int renderedChangeCount() { return children.size(); }

    @Override public void close() { closeChildren(); }
}
