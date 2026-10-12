package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService.EntityItem;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 同类型实体的 FXML 分组，负责其动态卡片生命周期。 */
public final class MemoryEntityGroupController implements AutoCloseable {
    @FXML private Region typeDot;
    @FXML private Label typeLabel;
    @FXML private FlowPane cards;

    private final MemoryComponentFactory components;
    private final List<MemoryChildView<?>> children = new ArrayList<>();

    public MemoryEntityGroupController(MemoryComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    void configure(String type, List<EntityItem> entities) {
        typeLabel.setText(type);
        typeDot.setStyle("-fx-background-color: " + color(type)
                + "; -fx-background-radius: 999;");
        closeChildren();
        for (EntityItem entity : entities) {
            MemoryChildView<javafx.scene.layout.HBox> child = components.entity(entity);
            children.add(child);
            cards.getChildren().add(child.root());
        }
    }

    private static String color(String type) {
        return switch (type == null ? "" : type.toLowerCase()) {
            case "project", "项目" -> "-jc-primary-400";
            case "tool", "工具" -> "-jc-accent-400";
            case "person", "人物", "人" -> "-jc-warning";
            case "topic", "主题", "concept", "概念" -> "-jc-primary-300";
            default -> "-jc-text-muted";
        };
    }

    private void closeChildren() {
        cards.getChildren().clear();
        children.forEach(MemoryChildView::close);
        children.clear();
    }

    @Override public void close() { closeChildren(); }
}
