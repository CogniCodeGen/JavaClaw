package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import com.javaclaw.ui.javafx.control.AccessibleActionPane;
import com.javaclaw.workflow.model.NodeDefinition;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

import java.util.Objects;

/** 工作流节点的 FXML 视图；不持有编辑器或应用服务。 */
public final class WorkflowNodeCard {

    @FXML private AccessibleActionPane root;
    @FXML private Label glyphLabel;
    @FXML private Label titleLabel;
    @FXML private Label badgeLabel;
    @FXML private Label idLabel;
    private final NodeDefinition node;

    WorkflowNodeCard(NodeDefinition node) {
        this.node = Objects.requireNonNull(node, "node");
        AccessibleActionPane loaded = EmbeddedFxmlLoader.load(
                WorkflowNodeCard.class.getResource("/fxml/workflow/workflow-node-card.fxml"),
                this, AccessibleActionPane.class);
        if (loaded != root) throw new IllegalStateException("工作流节点卡片 FXML 根节点不一致");
        render();
    }

    public AccessibleActionPane root() { return root; }

    public NodeDefinition node() { return node; }

    public void selected(boolean selected) {
        toggle("workflow-node-selected", selected);
    }

    public void connectionSource(boolean source) {
        toggle("workflow-node-connecting", source);
    }

    public void connectionTarget(boolean target) {
        toggle("workflow-node-connect-target", target);
    }

    private void render() {
        glyphLabel.setText(WorkflowLabels.nodeGlyph(node.type()));
        titleLabel.setText(node.label());
        badgeLabel.setText(WorkflowLabels.nodeName(node.type()));
        idLabel.setText(node.id());
        root.setAccessibleText(node.label() + "，" + WorkflowLabels.nodeName(node.type())
                + "，节点 " + node.id());
        root.getStyleClass().add("workflow-node-" + node.type().name().toLowerCase());
        root.relocate(node.x(), node.y());
    }

    private void toggle(String styleClass, boolean enabled) {
        root.getStyleClass().remove(styleClass);
        if (enabled) root.getStyleClass().add(styleClass);
    }
}
