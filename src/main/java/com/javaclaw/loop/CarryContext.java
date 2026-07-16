package com.javaclaw.loop;

import com.javaclaw.loop.model.CarryForwardMode;
import com.javaclaw.loop.model.IterationResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 接力上下文：累加历轮产出，并按 {@link CarryForwardMode} 决定喂给下一轮多少内容。
 *
 * <p>非线程安全——只在循环控制器的单一执行线程里被顺序读写。</p>
 */
public final class CarryContext {

    /** 单轮条目：带真实轮次号——失败轮被跳过后按列表下标编号会与控制器/UI 的轮次错位。 */
    private record RoundEntry(int round, String output, String summary) {}

    private final String goal;
    private final CarryForwardMode mode;
    private final List<RoundEntry> rounds = new ArrayList<>();
    /**
     * 已记录（含被跳过的失败轮）的轮次数：{@link #record} 每轮恰被控制器调用一次，
     * 计数与控制器的 iteration 同步推进，条目据此携带真实轮次号。
     */
    private int currentRound;
    private String lastOutput = "";
    /**
     * {@link #joinAll} 结果缓存：仅 {@link #record} 新增一轮时失效。消除同一轮内多条
     * output_contains 准则逐条核验、以及 FULL_TRANSCRIPT 每轮拼接时反复全量重建历轮输出的
     * O(N×M) 开销（rounds 只在 record 追加，故整轮内容不变期间缓存始终有效）。
     */
    private String joinAllCache;
    /** {@link #joinSummaries} 结果缓存（默认 SUMMARY 接力模式每轮都调，与 joinAllCache 对称失效）。 */
    private String joinSummariesCache;

    public CarryContext(String goal, CarryForwardMode mode) {
        this.goal = goal == null ? "" : goal;
        this.mode = mode == null ? CarryForwardMode.LAST_RESULT_ONLY : mode;
    }

    public String goal() {
        return goal;
    }

    /** 最近一轮的产出（供 output_contains 类准则即时核验）。 */
    public String lastOutput() {
        return lastOutput;
    }

    /** 记录本轮产出（每轮恰调用一次、在决策之前，使核验/接力都能看到本轮结果）。 */
    public void record(IterationResult result) {
        currentRound++; // 失败轮同样推进轮次计数（只计数不入上下文），保持与控制器轮次对齐
        // 异常/超时轮无有效产出，不入接力上下文：否则 lastOutput 被空串覆盖，纯文本目标
        // （草稿只活在 finalReply 里）遇一次瞬时错误即在下一轮提示词中全文蒸发。
        // 与 ProgressGate 对失败轮不推进比对基准的口径一致
        if (result != null && result.threw()) {
            return;
        }
        String output = result == null || result.finalReply() == null ? "" : result.finalReply();
        lastOutput = output;
        // 轮简述：优先取结构化汇报的 summary，缺失时截断产出兜底
        String summary = (result != null && result.report() != null
                && !result.report().summary().isBlank())
                ? result.report().summary()
                : truncate(output, LoopConstants.SUMMARY_FALLBACK_CHARS);
        rounds.add(new RoundEntry(currentRound, output, summary));
        // 历轮集合与末轮产出已变，两个拼接缓存一并失效（失败轮上面已早返回，不影响缓存）
        joinAllCache = null;
        joinSummariesCache = null;
    }

    /**
     * 全量累计输出（无视接力策略，始终拼接历轮全部产出）。
     *
     * <p>供 {@code output_contains} 类准则核验：完成判定要求全部准则「同轮」通过，
     * 若只对本轮输出匹配，早前轮已给出的关键内容会因收尾轮不复述而「丢失」，
     * 导致 done 几乎不可达。对累计输出匹配 = 事实一旦成立即粘住（与高水位语义一致）。</p>
     */
    public String cumulativeOutput() {
        return joinAll();
    }

    /** 供验收员参考的「迄今进展」文本，粒度由接力策略决定。 */
    public String transcript() {
        return switch (mode) {
            case FULL_TRANSCRIPT -> joinAll();
            // SUMMARY：历轮简述（来自 loop_report 的 summary，零额外成本）+ 末轮完整产出。
            // 上下文有界增长（每轮一句），又不像仅末轮那样丢失历史脉络
            case SUMMARY -> joinSummaries();
            case LAST_RESULT_ONLY -> lastOutput;
        };
    }

    /**
     * 组装下一轮的用户提示：<b>每轮都复述目标原文</b>（防止只依赖系统提示词的单点注入，
     * 也防止长循环中目标漂移），后续轮再带上此前进展。
     *
     * @param iteration 即将开始的轮次（从 1 起）
     */
    public String assemble(int iteration) {
        if (iteration <= 1) {
            return com.javaclaw.prompt.LoopPrompts.firstRoundPrompt(goal);
        }
        if (rounds.isEmpty()) {
            // 此前轮次全部失败被跳过：不谎称「开始第 1 轮」（与 UI 显示的轮次矛盾），
            // 如实告知无有效产出、按当前轮次重新推进
            return com.javaclaw.prompt.LoopPrompts.nextRoundPrompt(goal, iteration,
                    "（此前轮次均未产生有效产出，请重新推进目标）");
        }
        return com.javaclaw.prompt.LoopPrompts.nextRoundPrompt(goal, iteration, transcript());
    }

    private String joinAll() {
        if (joinAllCache != null) {
            return joinAllCache;
        }
        StringBuilder sb = new StringBuilder();
        for (RoundEntry e : rounds) {
            sb.append("第 ").append(e.round()).append(" 轮：\n").append(e.output()).append("\n\n");
        }
        joinAllCache = sb.toString().trim();
        return joinAllCache;
    }

    /** 历轮简述逐条列出（末轮附完整产出）。 */
    private String joinSummaries() {
        if (rounds.isEmpty()) {
            return lastOutput;
        }
        if (joinSummariesCache != null) {
            return joinSummariesCache;
        }
        StringBuilder sb = new StringBuilder("历轮进展简述：\n");
        for (RoundEntry e : rounds) {
            sb.append("第 ").append(e.round()).append(" 轮：").append(e.summary()).append("\n");
        }
        sb.append("\n最近一轮完整产出：\n").append(lastOutput);
        joinSummariesCache = sb.toString();
        return joinSummariesCache;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }
}
