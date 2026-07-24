package com.javaclaw.workflow.runtime;

public final class GraphCancelledException extends RuntimeException {
    public GraphCancelledException() { super("工作流已取消"); }
}
