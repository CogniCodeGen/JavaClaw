package com.javaclaw.ui.javafx.memory;

import javafx.fxml.FXML;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 图谱检视器关联记忆列表及动态 FXML 条目生命周期。 */
public final class MemoryRelatedListController implements AutoCloseable {
    @FXML private VBox items;
    private final MemoryComponentFactory components;
    private final List<MemoryChildView<?>> children = new ArrayList<>();

    public MemoryRelatedListController(MemoryComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    void configure(List<String> related) {
        closeChildren();
        List<String> values = related == null || related.isEmpty()
                ? List.of("（无直接关联）") : related;
        for (String value : values) {
            MemoryChildView<javafx.scene.layout.HBox> child = components.relatedMemory(value);
            children.add(child);
            items.getChildren().add(child.root());
        }
    }

    private void closeChildren() {
        items.getChildren().clear();
        children.forEach(MemoryChildView::close);
        children.clear();
    }

    @Override public void close() { closeChildren(); }
}
