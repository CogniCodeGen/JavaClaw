package com.javaclaw.loop;

import com.javaclaw.loop.model.CompletionCheck;
import com.javaclaw.loop.model.IterationResult;
import com.javaclaw.util.TextSimilarity;

import java.util.HashSet;
import java.util.Set;

/**
 * 继续判定器：多信号进展检测——「进展必须以状态变化为证据，而非文本变化」。
 *
 * <p>旧实现把「输出文本与上轮不同」当进展证据，但 LLM 换措辞重写即可轻松通过，
 * 空转也会被判有进展（曾导致无目标循环烧满轮数上限）。重写后的证据体系按可信度分层：</p>
 * <ul>
 *   <li><b>准则高水位前进</b>（最强）——已满足准则数<b>创新高</b>才算；用高水位而非
 *       「比上轮多」，防止「坏了又修好」的振荡反复刷出假进展；</li>
 *   <li><b>行动有新意</b>（强）——本轮工具调用指纹与上轮不同 = 真干了新的事；
 *       指纹一模一样 = 复读机行动，直接判停滞（同 ExecutionMonitor 同入参卡死思路）；</li>
 *   <li><b>自报剩余在变化</b>（弱，仅无准则目标采信）——连续两轮「剩余」高度相似
 *       = 执行体自己承认卡在同一处；</li>
 *   <li><b>输出新颖度</b>（极弱，仅无准则目标的最后兜底）——永远不能单独证明进展。</li>
 * </ul>
 *
 * <p>裁决规则：<b>有结构化准则的目标，进展必须体现为准则前进或新行动，纯文本变化不算；</b>
 * 无准则的自由目标才降级采信自报剩余与输出新颖度。</p>
 *
 * <p>有状态，非线程安全，只在单执行线程里顺序调用（{@link #madeProgress} 每轮恰好一次）。</p>
 */
public final class ProgressGate {

    /** 已满足准则数的历史高水位；初值 -1 保证首轮恒为「有进展」。 */
    private int highWaterSatisfied = -1;
    /** 上一轮的工具调用指纹集合。 */
    private Set<String> prevToolFingerprint = Set.of();
    /** 上一轮自报的「剩余」清单文本；null 表示上轮未报。 */
    private String prevRemaining;
    /** 上一轮输出全文（无准则目标的最后兜底比对用）。 */
    private String prevOutput = "";
    /** 是否首轮（无对照物，恒判有进展）。 */
    private boolean firstRound = true;

    /**
     * 本轮相对上轮是否前进。<b>每轮调用一次</b>（会推进内部状态）。
     */
    public boolean madeProgress(CompletionCheck check, IterationResult result) {
        // 异常/超时轮：无有效产出，不可能构成进展，也不推进比对基准——空回复对任何非空文本
        // 的相似度都是 0，若照常参与比对会把错误轮误判成「输出全新」，且让下一成功轮失去
        // 「上一轮正常输出」这个对照物，「复读 ↔ 报错」交替就能同时绕开停滞与连败两道护栏
        if (result.threw()) {
            return false;
        }
        // ---- 采集本轮证据 ----
        boolean criteriaAdvanced = check.satisfied() > highWaterSatisfied;

        Set<String> fingerprint = new HashSet<>(result.toolCalls());
        boolean actedNew = !fingerprint.isEmpty() && !fingerprint.equals(prevToolFingerprint);
        boolean repeatedActions = !fingerprint.isEmpty() && fingerprint.equals(prevToolFingerprint);

        String remaining = extractRemaining(result);
        boolean remainingStuck = remaining != null && prevRemaining != null
                && TextSimilarity.bigramJaccard(remaining, prevRemaining)
                        >= LoopConstants.REMAINING_SIMILARITY_THRESHOLD;
        boolean remainingChanged = remaining != null && prevRemaining != null && !remainingStuck;

        String output = result.finalReply();
        // 留存旧 prevOutput 供无准则目标的惰性输出新颖度比对（有准则目标下方提前返回、根本用不到，
        // 不在此处急算全文 bigram，省掉每轮 O(输出长度) 的分词白工）
        String prevOutputSnapshot = prevOutput;

        // ---- 推进状态（先采集后更新，比对基准始终是「上一轮」） ----
        boolean wasFirstRound = firstRound;
        firstRound = false;
        highWaterSatisfied = Math.max(highWaterSatisfied, check.satisfied());
        prevToolFingerprint = fingerprint;
        // 每轮都更新（含本轮无剩余时置 null）：remainingStuck 判的是「连续两轮」——若上轮没报剩余，
        // 本轮就没有相邻可比的基线。此前只在 remaining!=null 时更新，会把几轮前的陈旧剩余当基线，
        // 令「报剩余→静默轮→复述同一剩余」被误判为停滞、误杀仍在推进的循环
        prevRemaining = remaining;
        prevOutput = output == null ? "" : output;

        // ---- 分层裁决（按证据强度：准则创新高 ＞ 行动有无新意 ＞ 自报剩余 ＞ 输出新颖度）----
        if (wasFirstRound) return true;              // 首轮无对照，恒有进展
        if (criteriaAdvanced) return true;           // 最强证据：准则创新高
        if (repeatedActions) return false;           // 复读机行动：确定性停滞
        // 行动有新意（强肯定）必须先于自报剩余卡住（弱否定）：执行体每轮真干了不同的事、
        // 只是「剩余」措辞照抄上轮时，弱信号不得压过强信号误杀推进中的循环
        if (actedNew) return true;
        if (remainingStuck) return false;            // 无新行动 + 自我供认卡在同一处

        if (check.total() > 0) {
            // 有准则的目标：进展必须体现为准则前进或新行动（均已在上方判过）；纯文本变化不算
            return false;
        }
        // 无准则自由目标：剩余在变 / 输出新颖度兜底。输出新颖度到此才算（惰性）
        boolean outputFresh = TextSimilarity.bigramJaccard(prevOutputSnapshot, output)
                < LoopConstants.OUTPUT_SIMILARITY_THRESHOLD;
        return remainingChanged || outputFresh;
    }

    /**
     * 是否还有明确要做的事：有未满足准则，或执行体自己提议「未完成」。
     */
    public boolean hasRemaining(CompletionCheck check, IterationResult result) {
        if (!check.missing().isEmpty()) {
            return true;
        }
        // 结构化汇报优先：report 存在且 done=false 即为明确的「未完成」提议
        if (result.report() != null) {
            return !result.report().done();
        }
        return SentinelParser.proposesNotDone(result.finalReply());
    }

    /** 提取自报「剩余」：结构化汇报优先，缺失时降级到哨兵行解析。 */
    private static String extractRemaining(IterationResult result) {
        if (result.report() != null && !result.report().remaining().isBlank()) {
            return result.report().remaining();
        }
        return SentinelParser.extractRemaining(result.finalReply());
    }
}
