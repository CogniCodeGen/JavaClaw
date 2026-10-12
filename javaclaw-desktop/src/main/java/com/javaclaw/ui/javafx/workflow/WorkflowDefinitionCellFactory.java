package com.javaclaw.ui.javafx.workflow;

/** 创建构造时仅加载一次 FXML 的工作流定义 Cell。 */
public final class WorkflowDefinitionCellFactory {

    public WorkflowDefinitionCell create() {
        return new WorkflowDefinitionCell();
    }
}
