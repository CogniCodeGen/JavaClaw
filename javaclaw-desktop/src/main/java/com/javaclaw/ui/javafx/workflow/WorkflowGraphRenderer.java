package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.workflow.model.EdgeDefinition;
import com.javaclaw.workflow.model.GraphDefinition;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.Pane;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;

import java.util.Map;

/** 向 FXML 画布容器填充网格与边的无状态渲染算法。 */
final class WorkflowGraphRenderer {

    private WorkflowGraphRenderer() {
    }

    static void drawGrid(Pane grid) {
        if (!grid.getChildren().isEmpty()) return;
        for (int x = 0; x <= 1600; x += 32) {
            Line line = new Line(x, 0, x, 1000);
            line.getStyleClass().add(x % 160 == 0
                    ? "workflow-grid-major" : "workflow-grid-line");
            grid.getChildren().add(line);
        }
        for (int y = 0; y <= 1000; y += 32) {
            Line line = new Line(0, y, 1600, y);
            line.getStyleClass().add(y % 160 == 0
                    ? "workflow-grid-major" : "workflow-grid-line");
            grid.getChildren().add(line);
        }
    }

    static void drawEdges(
            Pane target,
            GraphDefinition graph,
            Map<String, WorkflowNodeCard> cards,
            EdgeMenuHandler menus) {
        target.getChildren().clear();
        if (graph == null) return;
        for (EdgeDefinition edge : graph.edges()) {
            drawEdge(target, edge, cards, menus);
        }
    }

    private static void drawEdge(
            Pane target,
            EdgeDefinition edge,
            Map<String, WorkflowNodeCard> cards,
            EdgeMenuHandler menus) {
        WorkflowNodeCard source = cards.get(edge.source());
        WorkflowNodeCard destination = cards.get(edge.target());
        if (source == null || destination == null) return;
        double x1 = source.root().getLayoutX() + Math.max(source.root().getWidth(), 184);
        double y1 = source.root().getLayoutY() + Math.max(source.root().getHeight(), 74) / 2;
        double x2 = destination.root().getLayoutX();
        double y2 = destination.root().getLayoutY()
                + Math.max(destination.root().getHeight(), 74) / 2;
        double bend = Math.max(48, Math.abs(x2 - x1) / 2);
        CubicCurve curve = new CubicCurve(x1, y1, x1 + bend, y1,
                x2 - bend, y2, x2, y2);
        curve.getStyleClass().addAll(
                "workflow-edge", "workflow-edge-" + edge.kind().name().toLowerCase());
        Tooltip.install(curve, new Tooltip(edge.kind() + (edge.defaultEdge() ? " · 默认" : "")));
        curve.setOnContextMenuRequested(event -> menus.show(edge, curve, event));
        Polygon arrow = new Polygon(x2, y2, x2 - 8, y2 - 4, x2 - 8, y2 + 4);
        arrow.getStyleClass().addAll(
                "workflow-edge-arrow", "workflow-edge-arrow-" + edge.kind().name().toLowerCase());
        arrow.setMouseTransparent(true);
        target.getChildren().addAll(curve, arrow);
    }

    @FunctionalInterface
    interface EdgeMenuHandler {
        void show(EdgeDefinition edge, CubicCurve curve, ContextMenuEvent event);
    }
}
