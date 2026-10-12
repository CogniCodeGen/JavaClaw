package com.javaclaw.agent.execution;

import com.javaclaw.framework.api.ToolExecutionStatus;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单次工具调用执行轨迹：记录工具名、结果摘要、成功状态与失败分类。
 *
 * <p>{@link FailureKind} 区分失败类型，便于评估流水线差异化处理：</p>
 * <ul>
 *   <li>{@link FailureKind#NONE} — 成功</li>
 *   <li>{@link FailureKind#TOOL_ERROR} — 工具明确报告失败</li>
 *   <li>{@link FailureKind#TIMEOUT} — 工具执行超时</li>
 *   <li>{@link FailureKind#UNKNOWN} — 缺少可信状态或结果不确定</li>
 *   <li>{@link FailureKind#SAME_INPUT_LOOP} — 同入参重复调用</li>
 * </ul>
 */
public class ExecutionTrace {

    public enum FailureKind { NONE, TOOL_ERROR, TIMEOUT, UNKNOWN, EMPTY_RESULT, SAME_INPUT_LOOP }

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_RESULT_LEN = 150;

    private final String toolName;
    private final String resultSummary;
    private final boolean success;
    private final FailureKind failureKind;
    /** 入参 hash —— 由调用方在创建 trace 后选择性回填，用于收敛检测。空表示未回填 */
    private String argsHash;
    private final String timestamp;

    public ExecutionTrace(String toolName, String result) {
        this(toolName, result, ToolExecutionStatus.UNKNOWN);
    }

    public ExecutionTrace(String toolName, String result, ToolExecutionStatus status) {
        this.toolName = toolName;
        String r = result != null ? result : "";
        this.resultSummary = r.length() > MAX_RESULT_LEN ? r.substring(0, MAX_RESULT_LEN) + "..." : r;
        this.failureKind = classify(status);
        this.success = failureKind == FailureKind.NONE;
        this.timestamp = LocalDateTime.now().format(FORMATTER);
    }

    private static FailureKind classify(ToolExecutionStatus status) {
        if (status == null) return FailureKind.UNKNOWN;
        return switch (status) {
            case SUCCEEDED -> FailureKind.NONE;
            case FAILED -> FailureKind.TOOL_ERROR;
            case TIMED_OUT -> FailureKind.TIMEOUT;
            case PENDING, UNCERTAIN, REOBSERVE, UNKNOWN -> FailureKind.UNKNOWN;
        };
    }

    public String getToolName() { return toolName; }
    public String getResultSummary() { return resultSummary; }
    public boolean isSuccess() { return success; }
    public FailureKind getFailureKind() { return failureKind; }
    public String getTimestamp() { return timestamp; }
    public String getArgsHash() { return argsHash; }
    public void setArgsHash(String argsHash) { this.argsHash = argsHash; }

    @Override
    public String toString() {
        return timestamp + " [" + toolName + "] " + (success ? "✓" : "✗") + " " + resultSummary;
    }
}
