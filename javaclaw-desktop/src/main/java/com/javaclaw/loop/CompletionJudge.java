package com.javaclaw.loop;

import com.javaclaw.agent.goal.SuccessCriterion;

/**
 * 模型验收端口：对「无法确定性核验」的目标/准则（描述性、外部检查）做独立判定。
 *
 * <p>刻意与执行体分离——干活的和验收的不是同一个，避免「自己给自己打分」。生产实现
 * （Phase 2）是一个带只读核验工具的轻量 critic 智能体，判定异常/超时一律保守判未达成。</p>
 */
public interface CompletionJudge {

    /**
     * 判定整体目标是否达成（用于无结构化准则的纯描述性目标）。
     *
     * @param goal       目标原文
     * @param transcript 迄今进展摘要
     */
    Verdict goalMet(String goal, String transcript);

    /**
     * 判定单条描述性/外部检查准则是否成立。
     *
     * @param criterion  待判定准则
     * @param transcript 迄今进展摘要
     */
    Verdict criterionMet(SuccessCriterion criterion, String transcript);

    /**
     * 进展仲裁：确定性进展信号判「停滞」时的复核——本轮产出相比上轮是否更接近目标。
     *
     * <p>专治无准则纯文本目标（如「反复润色文案」）：这类目标没有工具调用、剩余清单靠自报，
     * 确定性代码无法区分「实质推进」与「换措辞复述」。仲裁判「有进展」则赦免本次停滞记录，
     * 判「无进展」则停滞成立。<b>证据不足时应判无进展</b>（护栏优先，误烧受轮数上限兜底）。</p>
     *
     * @param goal           目标原文
     * @param previousOutput 上一轮产出
     * @param currentOutput  本轮产出
     * @return met=true 表示确认有实质进展（赦免停滞）
     */
    Verdict progressMade(String goal, String previousOutput, String currentOutput);

    /**
     * 取走自上次调用以来累计的模型用量（token 数，取后清零）。
     *
     * <p>验收/仲裁是循环自己发起的模型调用，控制器每轮把它并入 TOKEN_BUDGET 护栏与状态卡——
     * 不计入会让预算形同虚设（INTERVAL + judge=on 下每轮可对多条 freeform 准则各发起一次
     * 验收，实际花费可达执行体用量的数倍），状态卡数字也会系统性低于真实消耗。
     * 默认实现返回 0（测试假实现 / {@link #CONSERVATIVE_DENY} 无模型调用）。</p>
     */
    default long drainUsedTokens() {
        return 0;
    }

    /**
     * 判定结果。
     *
     * @param met    是否达成
     * @param reason 判定依据
     */
    record Verdict(boolean met, String reason) {}

    /**
     * 空实现：未启用模型验收时的保守降级——一律判未达成，绝不放行。
     *
     * <p>用于 {@code useJudge == false} 或未注入实现的场景，保证「宁可多跑，不放水」。</p>
     */
    CompletionJudge CONSERVATIVE_DENY = new CompletionJudge() {
        @Override
        public Verdict goalMet(String goal, String transcript) {
            return new Verdict(false, "未启用模型验收，保守判未达成");
        }

        @Override
        public Verdict criterionMet(SuccessCriterion criterion, String transcript) {
            return new Verdict(false, "未启用模型验收，保守判未达成");
        }

        @Override
        public Verdict progressMade(String goal, String previousOutput, String currentOutput) {
            return new Verdict(false, "未启用模型验收，停滞判定维持原判");
        }
    };
}
