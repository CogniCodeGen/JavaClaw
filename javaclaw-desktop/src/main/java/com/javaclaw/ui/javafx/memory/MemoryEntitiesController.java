package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.EntityItem;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 实体分区，按类型创建 FXML 分组。 */
public final class MemoryEntitiesController implements MemorySectionController, AutoCloseable {
    @FXML private VBox groups;
    @FXML private Label empty;
    private final MemoryComponentFactory components;
    private final List<MemoryChildView<?>> children = new ArrayList<>();

    public MemoryEntitiesController(MemoryComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    @Override
    public void apply(Snapshot snapshot, String query) {
        closeChildren();
        Map<String, List<EntityItem>> byType = new TreeMap<>();
        snapshot.entities().stream()
                .filter(item -> MemoryUiText.matches(query, item.name(), item.type()))
                .forEach(item -> byType.computeIfAbsent(item.type(), ignored -> new ArrayList<>())
                        .add(item));
        empty.setText(query == null || query.isBlank()
                ? "暂无实体（轮后实体抽取后长出）" : "没有匹配的实体");
        empty.setVisible(byType.isEmpty());
        empty.setManaged(byType.isEmpty());
        byType.forEach((type, entities) -> {
            MemoryChildView<VBox> child = components.entityGroup(type, entities);
            children.add(child);
            groups.getChildren().add(child.root());
        });
    }

    private void closeChildren() {
        groups.getChildren().clear();
        children.forEach(MemoryChildView::close);
        children.clear();
    }

    int renderedGroupCount() { return children.size(); }

    @Override public void close() { closeChildren(); }
}
