package com.javaclaw.application.tool;

import java.time.Duration;

/** 工具执行的统一领域结果。 */
public record ToolResult(
        Status status,
        String content,
        String errorType,
        String errorMessage,
        Duration duration) {

    public ToolResult {
        status = java.util.Objects.requireNonNull(status, "status");
        content = content == null ? "" : content;
        errorType = errorType == null ? "" : errorType;
        errorMessage = errorMessage == null ? "" : errorMessage;
        duration = duration == null ? Duration.ZERO : duration;
    }

    public boolean succeeded() {
        return status == Status.SUCCEEDED;
    }

    /** 面向 Agent/Shell 的稳定文本格式，不泄露堆栈。 */
    public String formatted() {
        return switch (status) {
            case SUCCEEDED -> content;
            case REJECTED -> "工具调用已拒绝：" + errorMessage;
            case FAILED -> "工具调用失败[" + errorType + "]：" + errorMessage;
        };
    }

    public enum Status {
        SUCCEEDED,
        REJECTED,
        FAILED
    }
}
