package com.javaclaw.workflow.store;

/** 工作流运行记录在预期存在时缺失。 */
public final class WorkflowRunMissingException extends IllegalStateException {
    public WorkflowRunMissingException(String message) {
        super(message);
    }

    public WorkflowRunMissingException(String message, Throwable cause) {
        super(message, cause);
    }
}
