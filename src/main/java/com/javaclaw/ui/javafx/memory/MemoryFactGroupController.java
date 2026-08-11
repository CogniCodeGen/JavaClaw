package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.FactItem;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 事实主题分组；每条事实由独立 FXML 片段渲染。 */
public final class MemoryFactGroupController implements AutoCloseable {
    @FXML private Label arrow;
    @FXML private Label title;
    @FXML private Label count;
    @FXML private VBox rowsArea;
    @FXML private VBox rows;

    private final MemoryComponentFactory components;
    private final List<MemoryChildView<?>> children = new ArrayList<>();
    private boolean expanded = true;
    private Consumer<Boolean> expandedChanged = ignored -> { };

    public MemoryFactGroupController(MemoryComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    void configure(
            String section,
            List<FactItem> facts,
            boolean batchMode,
            Set<String> selected,
            MemoryFactActions actions,
            boolean expanded,
            Consumer<Boolean> expandedChanged) {
        title.setText(section);
        count.setText(String.valueOf(facts.size()));
        this.expanded = expanded;
        this.expandedChanged = Objects.requireNonNull(expandedChanged, "expandedChanged");
        closeChildren();
        facts.stream().sorted(Comparator.comparing(FactItem::pinned).reversed()
                        .thenComparing(FactItem::updatedAt, Comparator.reverseOrder()))
                .forEach(fact -> {
                    MemoryChildView<javafx.scene.layout.HBox> child = components.factRow(
                            fact, batchMode, selected.contains(fact.id()), actions);
                    children.add(child);
                    rows.getChildren().add(child.root());
                });
        applyExpanded();
    }

    @FXML
    private void toggleExpanded() {
        expanded = !expanded;
        applyExpanded();
        expandedChanged.accept(expanded);
    }

    private void applyExpanded() {
        arrow.setText(expanded ? "▾" : "▸");
        rowsArea.setVisible(expanded);
        rowsArea.setManaged(expanded);
    }

    private void closeChildren() {
        rows.getChildren().clear();
        children.forEach(MemoryChildView::close);
        children.clear();
    }

    @Override public void close() { closeChildren(); }
}
