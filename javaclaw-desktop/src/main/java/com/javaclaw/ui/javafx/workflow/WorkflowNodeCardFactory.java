package com.javaclaw.ui.javafx.workflow;

import com.javaclaw.workflow.model.NodeDefinition;

/** 创建构造时仅加载一次 FXML 的画布节点卡片。 */
public final class WorkflowNodeCardFactory {

    public WorkflowNodeCard create(NodeDefinition node) {
        return new WorkflowNodeCard(node);
    }
}
