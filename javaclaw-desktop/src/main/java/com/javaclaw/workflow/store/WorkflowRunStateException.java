package com.javaclaw.workflow.store;

/** 工作流运行记录存在，但当前状态不允许所请求的转换。 */
public final class WorkflowRunStateException extends IllegalStateException {
    public WorkflowRunStateException(String message) {
        super(message);
    }

    public WorkflowRunStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
