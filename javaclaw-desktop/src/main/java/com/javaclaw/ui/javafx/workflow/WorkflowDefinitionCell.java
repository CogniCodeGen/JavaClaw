package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.application.workflow.WorkflowApplicationService.WorkflowItem;
import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;

/** 虚拟化工作流列表 Cell；构造时加载模板，复用时只更新展示状态。 */
public final class WorkflowDefinitionCell extends ListCell<WorkflowItem> {

    @FXML private HBox root;
    @FXML private Label iconLabel;
    @FXML private Label nameLabel;
    @FXML private Label badgeLabel;

    WorkflowDefinitionCell() {
        HBox loaded = EmbeddedFxmlLoader.load(
                WorkflowDefinitionCell.class.getResource(
                        "/fxml/workflow/workflow-definition-cell.fxml"),
                this, HBox.class);
        if (loaded != root) throw new IllegalStateException("工作流定义 Cell FXML 根节点不一致");
    }

    @Override
    protected void updateItem(WorkflowItem item, boolean empty) {
        super.updateItem(item, empty);
        setText(null);
        if (empty || item == null) {
            setGraphic(null);
            return;
        }
        iconLabel.setText(item.system() ? "◆" : "◇");
        nameLabel.setText(item.name());
        badgeLabel.setText(item.system() ? "系统" : item.published() ? "已发布" : "草稿");
        badgeLabel.getStyleClass().removeAll(
                "workflow-badge-system", "jc-badge-ok", "jc-badge-stopped");
        badgeLabel.getStyleClass().add(item.system() ? "workflow-badge-system"
                : item.published() ? "jc-badge-ok" : "jc-badge-stopped");
        setAccessibleText(item.name() + "，" + badgeLabel.getText());
        setGraphic(root);
    }
}
