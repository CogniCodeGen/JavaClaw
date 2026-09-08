package com.javaclaw.desktop.view;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;
import com.javaclaw.desktop.web.WebSurfaceHost;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;

/** 只解释声明式节点和边；图布局及页面脚本属于平台，所有选择仍经过 ViewSchema 的草稿和 revision 保护。 */
final class ViewGraphRenderer {
    private final PlatformComponentFactory components;

    ViewGraphRenderer(PlatformComponentFactory components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    GraphView create(ViewSchema.Graph schema) {
        return create(schema, false);
    }

    GraphView create(ViewSchema.Graph schema, boolean browsing) {
        return new GraphView(schema, browsing);
    }

    final class GraphView implements AutoCloseable {
        private final ViewSchema.Graph schema;
        private final boolean browsing;
        private final ListView<Map<String, Object>> nativeNodes = new ListView<>();
        private final Label detail = new Label();
        private final VBox nativeEdges = new VBox(4);
        private final WebSurfaceHost surface;
        private final VBox root;
        private final Button expand;
        private final Map<String, Map<String, Object>> available = new LinkedHashMap<>();
        private ViewData.Source source;
        private List<Map<String, Object>> edges = List.of();
        private ViewInteractionHandler interactions;
        private int limit = 200;
        private boolean updating;

        GraphView(ViewSchema.Graph schema, boolean browsing) {
            this.schema = schema;
            this.browsing = browsing;
            nativeNodes.getStyleClass().add("platform-data-list");
            nativeNodes.setCellFactory(ignored -> components.detailCell(
                    row -> value(row, schema.nodeLabelField()), row -> value(row, schema.nodeKindField())));
            nativeNodes.getSelectionModel().selectedItemProperty().addListener((observable, previous, row) -> {
                if (!updating && row != null && interactions != null) {
                    interactions.select(schema.nodeSourceId(), Optional.of(value(row, schema.nodeIdField())));
                }
            });
            surface = new WebSurfaceHost("graph", new VBox(8, nativeNodes, nativeEdges), (action, value) -> {
                if (action.equals("select") && available.containsKey(value) && interactions != null) {
                    interactions.select(schema.nodeSourceId(), Optional.of(value));
                }
            });
            surface.setMinHeight(320);
            surface.setPrefHeight(420);
            VBox.setVgrow(surface, Priority.ALWAYS);
            detail.getStyleClass().add("sec-hint");
            expand = components.action("展开 50 个", ActionStyle.SOFT, ActionSize.COMPACT);
            expand.setOnAction(event -> expand());
            Button simple = components.action("简版", ActionStyle.GHOST, ActionSize.COMPACT);
            simple.setOnAction(event -> surface.useFallback());
            Button retry = components.action("重试显示", ActionStyle.GHOST, ActionSize.COMPACT);
            retry.setOnAction(event -> surface.retry());
            root = components.section(schema.title(), new HBox(8, detail, expand, simple, retry), surface);
            if (browsing) {
                root.getChildren().add(1, filters());
            }
        }

        Node node() {
            return root;
        }

        void apply(ViewData data, ViewInteractionHandler handler) {
            interactions = handler;
            source = data.source(schema.nodeSourceId());
            if (!available.isEmpty()) {
                limit = Math.min(500, Math.max(limit, source.rows().size()));
            }
            if (source.pageIndex() == 0) {
                available.clear();
            }
            for (Map<String, Object> row : source.rows()) {
                String id = value(row, schema.nodeIdField());
                if (!id.isBlank() && (available.size() < 500 || available.containsKey(id))) {
                    available.put(id, row);
                }
            }
            edges = data.source(schema.edgeSourceId()).rows();
            project();
        }

        private Node filters() {
            TextField query = new TextField();
            query.setPromptText("筛选图谱");
            query.setTextFormatter(new javafx.scene.control.TextFormatter<String>(
                    change -> change.getControlNewText().length() <= 200 ? change : null));
            CheckBox inactive = new CheckBox("包含历史项");
            Button apply = components.action("筛选", ActionStyle.SOFT, ActionSize.COMPACT);
            Runnable action = () -> interactions.graph(new ViewGraphAction(
                    schema.id(), Optional.of(new ViewGraphAction.Filter(query.getText(), inactive.isSelected())), ""));
            apply.setOnAction(event -> action.run());
            query.setOnAction(event -> action.run());
            return new HBox(8, query, inactive, apply);
        }

        private void expand() {
            if (browsing) {
                source.selectedKey()
                        .ifPresent(id -> interactions.graph(new ViewGraphAction(schema.id(), Optional.empty(), id)));
            } else if (limit < Math.min(500, available.size())) {
                limit = Math.min(500, limit + 50);
                project();
            } else if (source != null && source.hasMore() && available.size() < 500) {
                limit = Math.min(500, limit + 50);
                interactions.page(schema.nodeSourceId(), ViewPageDirection.NEXT);
            }
        }

        private void project() {
            List<Map<String, Object>> rows =
                    available.values().stream().limit(limit).toList();
            updating = true;
            try {
                nativeNodes.getItems().setAll(rows);
                source.selectedKey()
                        .flatMap(key -> rows.stream()
                                .filter(row -> key.equals(value(row, schema.nodeIdField())))
                                .findFirst())
                        .ifPresent(nativeNodes.getSelectionModel()::select);
            } finally {
                updating = false;
            }
            List<Map<String, Object>> elements = elements(rows);
            nativeEdges.getChildren().clear();
            int rejected = 0;
            Set<String> ids = rows.stream()
                    .map(row -> value(row, schema.nodeIdField()))
                    .collect(java.util.stream.Collectors.toSet());
            for (Map<String, Object> edge : edges) {
                String from = value(edge, schema.edgeFromField());
                String to = value(edge, schema.edgeToField());
                if (!ids.contains(from) || !ids.contains(to)) {
                    rejected++;
                } else if (nativeEdges.getChildren().size() < 50) {
                    nativeEdges.getChildren().add(new Label(from + " → " + to));
                }
            }
            if (rejected > 0) {
                nativeEdges.getChildren().add(new Label("已忽略 " + rejected + " 条端点不存在的边"));
            }
            int edgeCount = elements.size() - rows.size();
            detail.setText(rows.size() + " 个节点 · " + edgeCount + " 条关系" + (available.size() >= 500 ? " · 已到展示上限" : ""));
            expand.setDisable(rows.size() >= 500
                    || (browsing
                            ? source.selectedKey().isEmpty()
                            : rows.size() >= available.size() && !source.hasMore()));
            surface.show(
                    schema.id(),
                    new CanonicalJson()
                            .encode(Map.of(
                                    "elements",
                                    elements,
                                    "selected",
                                    source.selectedKey().orElse("")))
                            .json());
        }

        private List<Map<String, Object>> elements(List<Map<String, Object>> rows) {
            List<Map<String, Object>> result = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (Map<String, Object> row : rows) {
                String id = value(row, schema.nodeIdField());
                ids.add(id);
                result.add(Map.of(
                        "data",
                        Map.of(
                                "id",
                                id,
                                "label",
                                value(row, schema.nodeLabelField()),
                                "kind",
                                value(row, schema.nodeKindField()))));
            }
            int count = 0;
            for (Map<String, Object> row : edges) {
                String from = value(row, schema.edgeFromField());
                String to = value(row, schema.edgeToField());
                if (ids.contains(from) && ids.contains(to) && count < 1000) {
                    String id = "edge:" + count++ + ':' + from + ':' + to;
                    result.add(Map.of("data", Map.of("id", id, "source", from, "target", to)));
                }
            }
            return result;
        }

        void suspend() {
            surface.suspend();
        }

        void resume() {
            surface.resume();
        }

        @Override
        public void close() {
            surface.close();
            available.clear();
            edges = List.of();
            nativeNodes.getItems().clear();
            interactions = null;
        }
    }

    private static String value(Map<String, Object> row, String field) {
        return Objects.toString(row.get(field), "");
    }
}
