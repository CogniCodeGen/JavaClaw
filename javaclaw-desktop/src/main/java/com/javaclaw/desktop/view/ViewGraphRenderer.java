package com.javaclaw.desktop.view;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.FeedbackKind;
import com.javaclaw.extension.spi.ViewSchema;

/** 把声明式节点和边渲染为平台拥有的只读 Graph 控件。 */
final class ViewGraphRenderer {
    private final PlatformComponentFactory components;

    ViewGraphRenderer(PlatformComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    Node render(ViewSchema.Graph schema, ViewData data) {
        ViewData.Source nodes = data.source(schema.nodeSourceId());
        ViewData.Source edges = data.source(schema.edgeSourceId());
        if (nodes.rows().isEmpty()) {
            return components.section(
                    schema.title(), components.feedback(FeedbackKind.EMPTY, "暂无图节点", "扩展当前没有返回可展示的 Graph 数据。"));
        }
        Set<String> nodeIds = new HashSet<>();
        VBox nodeList = new VBox(6);
        nodeList.getStyleClass().add("platform-graph-nodes");
        for (Map<String, Object> row : nodes.rows()) {
            String id = value(row, schema.nodeIdField());
            nodeIds.add(id);
            nodeList.getChildren().add(node(schema, row, id));
        }
        VBox edgeList = new VBox(4);
        edgeList.getStyleClass().add("platform-graph-edges");
        int rejected = 0;
        for (Map<String, Object> row : edges.rows()) {
            String from = value(row, schema.edgeFromField());
            String to = value(row, schema.edgeToField());
            if (!nodeIds.contains(from) || !nodeIds.contains(to)) {
                rejected++;
                continue;
            }
            Label edge = new Label(from + " → " + to);
            edge.getStyleClass().add("platform-graph-edge");
            edgeList.getChildren().add(edge);
        }
        VBox content = new VBox(10, nodeList, edgeList);
        if (rejected > 0) {
            Label warning = new Label("已忽略 " + rejected + " 条端点不存在的边");
            warning.getStyleClass().add("sec-hint");
            content.getChildren().add(warning);
        }
        content.getStyleClass().add("platform-graph");
        return components.section(schema.title(), content);
    }

    private Node node(ViewSchema.Graph schema, Map<String, Object> row, String id) {
        Label kind = new Label(value(row, schema.nodeKindField()));
        kind.getStyleClass().add("platform-graph-node-kind");
        Label label = new Label(value(row, schema.nodeLabelField()));
        label.getStyleClass().add("platform-body-text");
        Label identity = new Label(id);
        identity.getStyleClass().add("sec-hint");
        HBox node = new HBox(8, kind, label, identity);
        node.getStyleClass().add("platform-graph-node");
        return node;
    }

    private String value(Map<String, Object> row, String field) {
        return Objects.toString(row.get(field), "");
    }
}
