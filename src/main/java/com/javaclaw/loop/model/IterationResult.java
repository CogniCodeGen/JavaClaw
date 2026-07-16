package com.javaclaw.loop.model;

import java.util.List;

/**
 * 单轮执行产出：由 {@code LoopIterationRunner} 跑完一轮后返回。
 *
 * @param finalReply   本轮执行体的最终回复全文（含结尾的自报判定行）；null 归一为空串
 * @param threw        本轮是否异常/超时（用于连续失败检测）
 * @param inputTokens  本轮输入用量
 * @param outputTokens 本轮输出用量
 * @param toolCalls    本轮工具调用指纹列表（工具名+<b>入参</b>哈希，见 {@code ToolFingerprintHook}），
 *                     供进展判定比对「行动是否有新意」——同一组指纹重复出现即复读机行动。
 *                     刻意取入参而非结果：同一命令的输出天然含耗时/时间戳/PID 噪声，哈希结果会
 *                     令每次重放都成新指纹、复读机检测永不触发
 * @param report       结构化轮次汇报（loop_report 工具提交）；null 表示模型未按协议汇报，
 *                     下游降级到哨兵行文本解析
 */
public record IterationResult(String finalReply, boolean threw,
                              long inputTokens, long outputTokens,
                              List<String> toolCalls, LoopReport report) {

    public IterationResult {
        if (finalReply == null) finalReply = "";
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 正常完成的一轮（无工具调用记录、无结构化汇报）。 */
    public static IterationResult ok(String finalReply, long inputTokens, long outputTokens) {
        return new IterationResult(finalReply, false, inputTokens, outputTokens, List.of(), null);
    }

    /** 正常完成的一轮（带工具调用指纹，无结构化汇报）。 */
    public static IterationResult ok(String finalReply, long inputTokens, long outputTokens,
                                     List<String> toolCalls) {
        return new IterationResult(finalReply, false, inputTokens, outputTokens, toolCalls, null);
    }

    /** 正常完成的一轮（带工具调用指纹与结构化汇报）。 */
    public static IterationResult ok(String finalReply, long inputTokens, long outputTokens,
                                     List<String> toolCalls, LoopReport report) {
        return new IterationResult(finalReply, false, inputTokens, outputTokens, toolCalls, report);
    }

    /** 异常/超时的一轮（无有效产出、无已计量用量）。 */
    public static IterationResult failed() {
        return failed(0L, 0L);
    }

    /**
     * 异常/超时的一轮，但保留失败前已实际消耗的用量。
     *
     * <p>超时轮可能已烧掉数万 token（多次模型往返后才卡死），归零会让 TOKEN_BUDGET
     * 预算护栏与状态卡的累计用量双双低估，预算烧穿后循环仍继续开新轮。</p>
     */
    public static IterationResult failed(long inputTokens, long outputTokens) {
        return new IterationResult("", true, inputTokens, outputTokens, List.of(), null);
    }
}
