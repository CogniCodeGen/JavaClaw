package com.javaclaw.ui.javafx.workflow;

/** 创建构造时仅加载一次 FXML 的工作流运行记录 Cell。 */
public final class WorkflowRunCellFactory {

    public WorkflowRunCell create() {
        return new WorkflowRunCell();
    }
}
