package com.javaclaw.desktop;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.javaclaw.sdk.model.AutomationDefinitionInfo;

/** 使用现有 Node DTO 展示 Workflow 节点、连接、Inspector 与即时结构校验。 */
final class WorkflowGraphEditor extends BorderPane {
    private final ListView<AutomationDefinitionInfo.Node> nodes;
    private final VBox canvas = new VBox(8);
    private final VBox inspector = new VBox(10);
    private final Label validation = new Label();

    WorkflowGraphEditor(ListView<AutomationDefinitionInfo.Node> nodes, Button add, Button edit, Button remove) {
        this.nodes = nodes;
        getStyleClass().add("workflow-graph-editor");
        validation.setWrapText(true);
        validation.getStyleClass().add("workflow-validation");
        canvas.setPadding(new Insets(12));
        canvas.getStyleClass().add("workflow-canvas");
        inspector.setPadding(new Insets(12));
        inspector.setMinWidth(240);
        inspector.setPrefWidth(280);
        inspector.getStyleClass().add("workflow-inspector");

        var canvasScroll = new ScrollPane(canvas);
        canvasScroll.setFitToWidth(true);
        canvasScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        canvasScroll.getStyleClass().add("modal-content-area");
        var body = new BorderPane(canvasScroll);
        body.setRight(inspector);
        setCenter(body);
        setTop(new VBox(8, ManagementForms.hint("节点画布 · 选择节点查看 Inspector；连线仍使用稳定节点 id。"), validation));
        setBottom(ManagementForms.actions(add, edit, remove));

        nodes.getItems().addListener((ListChangeListener<AutomationDefinitionInfo.Node>) ignored -> rebuild());
        nodes.getSelectionModel().selectedItemProperty().addListener((ignored, old, value) -> rebuild());
        ManagementForms.guard(
                edit, nodes.getSelectionModel().selectedItemProperty().isNull());
        ManagementForms.guard(
                remove, nodes.getSelectionModel().selectedItemProperty().isNull());
        rebuild();
    }

    private void rebuild() {
        canvas.getChildren().clear();
        if (nodes.getItems().isEmpty()) {
            canvas.getChildren().add(ManagementForms.emptyState("◇", "画布为空", "添加 START、执行节点和 END 后保存。"));
        } else {
            for (AutomationDefinitionInfo.Node node : nodes.getItems()) {
                canvas.getChildren().add(card(node));
                if (!node.next().isBlank() || !node.otherwise().isBlank()) {
                    Label edge = new Label(edgeText(node));
                    edge.getStyleClass().add("workflow-edge");
                    canvas.getChildren().add(edge);
                }
            }
        }
        String problem = validate(nodes.getItems());
        validation.setText(problem == null ? "图结构预检通过；保存时仍由 App Server 执行权威校验。" : "图结构问题：" + problem);
        validation.getStyleClass().removeAll("workflow-validation-ok", "workflow-validation-error");
        validation.getStyleClass().add(problem == null ? "workflow-validation-ok" : "workflow-validation-error");
        inspect(nodes.getSelectionModel().getSelectedItem());
    }

    private HBox card(AutomationDefinitionInfo.Node node) {
        Label kind = new Label(DesktopPresentationMapper.status(node.kind()));
        kind.getStyleClass().add("management-status-badge");
        Label id = new Label(node.id());
        id.getStyleClass().add("workflow-node-title");
        Label summary = new Label(summary(node));
        summary.setWrapText(true);
        summary.getStyleClass().add("workflow-node-summary");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        var card = new HBox(10, kind, new VBox(3, id, summary), spacer);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("workflow-node-card");
        card.setAccessibleText("Workflow 节点 " + node.id() + "，" + node.kind());
        card.setOnMouseClicked(ignored -> nodes.getSelectionModel().select(node));
        if (node.equals(nodes.getSelectionModel().getSelectedItem())) {
            card.getStyleClass().add("workflow-node-selected");
        }
        return card;
    }

    private void inspect(AutomationDefinitionInfo.Node node) {
        inspector.getChildren().clear();
        Label title = new Label("节点 Inspector");
        title.getStyleClass().add("management-section-title");
        inspector.getChildren().add(title);
        if (node == null) {
            inspector.getChildren().add(ManagementForms.hint("在画布中选择一个节点。"));
            return;
        }
        inspector
                .getChildren()
                .addAll(
                        property("标识", node.id()),
                        property("类型", DesktopPresentationMapper.status(node.kind())),
                        property("下一节点", DesktopPresentationMapper.text(node.next(), "结束")),
                        property("其他分支", DesktopPresentationMapper.text(node.otherwise(), "无")),
                        property("最多访问", Integer.toString(node.maxVisits())),
                        property(
                                "参数",
                                node.parameters().isEmpty()
                                        ? "无"
                                        : node.parameters().entrySet().stream()
                                                .sorted(java.util.Map.Entry.comparingByKey())
                                                .map(entry -> entry.getKey() + " = " + entry.getValue())
                                                .collect(Collectors.joining("\n"))));
    }

    private static VBox property(String name, String value) {
        Label heading = new Label(name);
        heading.getStyleClass().add("form-label");
        Label body = new Label(value);
        body.setWrapText(true);
        body.getStyleClass().add("workflow-inspector-value");
        return new VBox(3, heading, body);
    }

    private static String summary(AutomationDefinitionInfo.Node node) {
        return node.parameters().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .limit(2)
                .map(entry -> entry.getKey() + "：" + entry.getValue())
                .collect(Collectors.joining(" · "));
    }

    private static String edgeText(AutomationDefinitionInfo.Node node) {
        String primary = node.next().isBlank() ? "结束" : node.next();
        return node.otherwise().isBlank() ? "↓  " + primary : "↓ 成功：" + primary + "    分支：" + node.otherwise();
    }

    static String validate(List<AutomationDefinitionInfo.Node> nodes) {
        if (nodes.isEmpty()) {
            return "至少需要一个 START 和一个 END 节点";
        }
        Set<String> ids = new LinkedHashSet<>();
        for (AutomationDefinitionInfo.Node node : nodes) {
            if (node.id().isBlank()) {
                return "节点 id 不能为空";
            }
            if (!ids.add(node.id())) {
                return "节点 id 重复：" + node.id();
            }
            if (node.maxVisits() < 1) {
                return node.id() + " 的访问上限必须为正数";
            }
        }
        List<AutomationDefinitionInfo.Node> starts =
                nodes.stream().filter(node -> "START".equals(node.kind())).toList();
        if (starts.size() != 1) {
            return "必须且只能有一个 START 节点";
        }
        if (nodes.stream().noneMatch(node -> "END".equals(node.kind()))) {
            return "至少需要一个 END 节点";
        }
        for (AutomationDefinitionInfo.Node node : nodes) {
            for (String target : List.of(node.next(), node.otherwise())) {
                if (!target.isBlank() && !ids.contains(target)) {
                    return node.id() + " 指向不存在的节点 " + target;
                }
            }
        }
        Set<String> reachable = reachable(nodes, starts.getFirst().id());
        List<String> missing =
                ids.stream().filter(id -> !reachable.contains(id)).toList();
        return missing.isEmpty() ? null : "存在不可达节点：" + String.join("、", missing);
    }

    private static Set<String> reachable(List<AutomationDefinitionInfo.Node> nodes, String start) {
        var byId = nodes.stream().collect(Collectors.toMap(AutomationDefinitionInfo.Node::id, value -> value));
        Set<String> seen = new HashSet<>();
        var pending = new ArrayDeque<String>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!seen.add(id)) {
                continue;
            }
            AutomationDefinitionInfo.Node node = byId.get(id);
            if (node == null) {
                continue;
            }
            if (!node.next().isBlank()) {
                pending.addLast(node.next());
            }
            if (!node.otherwise().isBlank()) {
                pending.addLast(node.otherwise());
            }
        }
        return seen;
    }
}
