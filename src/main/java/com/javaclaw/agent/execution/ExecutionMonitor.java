package com.javaclaw.agent.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 执行监控器（GEPA 之 Execution）：追踪工具调用执行轨迹，差异化检测多种异常信号。
 *
 * <p>三类信号会被独立检测并触发回调：</p>
 * <ol>
 *   <li><b>连续失败</b> — 同工具连续失败次数达阈值，{@link #onConsecutiveFailure}</li>
 *   <li><b>同入参收敛卡死</b> — 同 (toolName, argsHash) 连续 3 次调用，{@link #onConvergenceStuck}</li>
 *   <li><b>成功率下滑</b> — 滑窗成功率低于阈值（外部按需读取 {@link #successRate()}）</li>
 * </ol>
 *
 * <p>上游调用方（ChatService）只把真正的 {@code ConversationEvent.ToolResult}
 * 送进本监控器，子智能体转发事件已在上游过滤，因此这里无需二次过滤。</p>
 */
public class ExecutionMonitor {

    private static final Logger log = LoggerFactory.getLogger(ExecutionMonitor.class);

    /** 同一工具连续失败触发告警的阈值 */
    private static final int CONSECUTIVE_FAIL_THRESHOLD = 2;
    /** 同入参重复调用触发告警的阈值 */
    private static final int SAME_INPUT_LOOP_THRESHOLD = 3;
    /** 成功率滑窗大小 */
    private static final int SUCCESS_RATE_WINDOW = 20;

    /** 全量轨迹列表（访问时需同步） */
    private final List<ExecutionTrace> traces = new ArrayList<>();

    /** 各工具的连续失败计数（线程安全） */
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    /** 各工具最近 N 次的 (argsHash) 列表，用于收敛检测 */
    private final Map<String, Deque<String>> recentArgs = new ConcurrentHashMap<>();

    /** 滑窗：最近 N 次调用的成功标志 */
    private final Deque<Boolean> successWindow = new ArrayDeque<>();

    /** 连续失败回调（参数为工具名称） */
    private volatile Consumer<String> onConsecutiveFailure;

    /** 同入参收敛卡死回调（参数为工具名称） */
    private volatile Consumer<String> onConvergenceStuck;

    /**
     * 重置执行状态（每次 streamChat 开始前调用）
     */
    public void reset() {
        synchronized (traces) { traces.clear(); }
        consecutiveFailures.clear();
        recentArgs.clear();
        synchronized (successWindow) { successWindow.clear(); }
    }

    public void setOnConsecutiveFailure(Consumer<String> callback) {
        this.onConsecutiveFailure = callback;
    }

    public void setOnConvergenceStuck(Consumer<String> callback) {
        this.onConvergenceStuck = callback;
    }

    /**
     * 记录一次工具执行结果。
     *
     * @param toolName 工具名称
     * @param result   工具执行结果
     */
    public void recordExecution(String toolName, String result) {
        recordExecution(toolName, null, result);
    }

    /**
     * 记录一次工具执行结果（带入参 hash 用于收敛检测）。
     *
     * @param toolName 工具名称
     * @param argsHash 入参摘要（null 时跳过收敛检测）
     * @param result   工具执行结果
     */
    public void recordExecution(String toolName, String argsHash, String result) {
        if (result == null) return;

        ExecutionTrace trace = new ExecutionTrace(toolName, result);
        if (argsHash != null) trace.setArgsHash(argsHash);
        synchronized (traces) { traces.add(trace); }

        synchronized (successWindow) {
            successWindow.addLast(trace.isSuccess());
            if (successWindow.size() > SUCCESS_RATE_WINDOW) successWindow.removeFirst();
        }

        if (trace.isSuccess()) {
            consecutiveFailures.remove(toolName);
        } else {
            int failures = consecutiveFailures.merge(toolName, 1, Integer::sum);
            log.warn("工具 [{}] 连续失败 {} 次（kind={}）", toolName, failures, trace.getFailureKind());
            if (failures >= CONSECUTIVE_FAIL_THRESHOLD) {
                Consumer<String> cb = onConsecutiveFailure;
                if (cb != null) cb.accept(toolName);
            }
        }

        if (argsHash != null) detectConvergence(toolName, argsHash);
    }

    private void detectConvergence(String toolName, String argsHash) {
        Deque<String> recent = recentArgs.computeIfAbsent(toolName,
                k -> new ArrayDeque<>(SAME_INPUT_LOOP_THRESHOLD));
        synchronized (recent) {
            recent.addLast(argsHash);
            while (recent.size() > SAME_INPUT_LOOP_THRESHOLD) recent.removeFirst();
            if (recent.size() == SAME_INPUT_LOOP_THRESHOLD
                    && recent.stream().allMatch(argsHash::equals)) {
                log.warn("工具 [{}] 同入参连续调用 {} 次，疑似收敛卡死", toolName, recent.size());
                Consumer<String> cb = onConvergenceStuck;
                if (cb != null) cb.accept(toolName);
                recent.clear(); // 触发后清空，避免连续轰炸
            }
        }
    }

    /**
     * 获取全部执行轨迹的只读副本
     */
    public List<ExecutionTrace> getTraces() {
        synchronized (traces) { return List.copyOf(traces); }
    }

    /**
     * 获取失败轨迹列表
     */
    public List<ExecutionTrace> getFailedTraces() {
        synchronized (traces) {
            return traces.stream().filter(t -> !t.isSuccess()).toList();
        }
    }

    /**
     * 滑窗成功率（最近 {@value #SUCCESS_RATE_WINDOW} 次调用）；
     * 调用次数不足时返回 1.0（视为没问题，避免误警）。
     */
    public double successRate() {
        synchronized (successWindow) {
            if (successWindow.isEmpty()) return 1.0;
            long ok = successWindow.stream().filter(Boolean::booleanValue).count();
            return (double) ok / successWindow.size();
        }
    }

    /**
     * 获取最近 N 条轨迹的文本摘要
     */
    public String buildTraceSummary(int maxTraces) {
        List<ExecutionTrace> snapshot;
        synchronized (traces) {
            int from = Math.max(0, traces.size() - maxTraces);
            snapshot = new ArrayList<>(traces.subList(from, traces.size()));
        }
        if (snapshot.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ExecutionTrace t : snapshot) sb.append(t).append("\n");
        return sb.toString();
    }

    public int getTraceCount() {
        synchronized (traces) { return traces.size(); }
    }
}
