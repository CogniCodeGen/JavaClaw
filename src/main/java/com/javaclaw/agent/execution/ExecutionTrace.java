package com.javaclaw.agent.execution;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 单次工具调用执行轨迹：记录工具名、结果摘要、成功状态与失败分类。
 *
 * <p>{@link FailureKind} 区分失败类型，便于评估流水线差异化处理：</p>
 * <ul>
 *   <li>{@link FailureKind#NONE} — 成功</li>
 *   <li>{@link FailureKind#TOOL_ERROR} — 工具内部抛错（[失败] / error 关键字）</li>
 *   <li>{@link FailureKind#TIMEOUT} — 工具执行超时</li>
 *   <li>{@link FailureKind#EMPTY_RESULT} — 工具成功但产出为空</li>
 *   <li>{@link FailureKind#SAME_INPUT_LOOP} — 同入参重复调用（由 ExecutionMonitor 标注）</li>
 * </ul>
 */
public class ExecutionTrace {

    public enum FailureKind { NONE, TOOL_ERROR, TIMEOUT, EMPTY_RESULT, SAME_INPUT_LOOP }

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
        this.toolName = toolName;
        String r = result != null ? result : "";
        this.resultSummary = r.length() > MAX_RESULT_LEN ? r.substring(0, MAX_RESULT_LEN) + "..." : r;
        this.failureKind = classify(r);
        this.success = failureKind == FailureKind.NONE;
        this.timestamp = LocalDateTime.now().format(FORMATTER);
    }

    private static FailureKind classify(String raw) {
        String lower = raw == null ? "" : raw.toLowerCase();
        if (lower.contains("[超时]") || lower.contains("timeout")) return FailureKind.TIMEOUT;
        if (lower.contains("[失败]") || lower.contains("[error]") || lower.contains("失败")) {
            return FailureKind.TOOL_ERROR;
        }
        if (raw == null || raw.isBlank()) return FailureKind.EMPTY_RESULT;
        return FailureKind.NONE;
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
