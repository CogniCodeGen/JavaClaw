package com.javaclaw.prompt;

import com.javaclaw.loop.LoopConstants;

/**
 * 循环子系统提示词集中管理。
 *
 * <p>只承载两段提示词正文：循环执行体（干活的）与验收员（把关的）。目标分解复用
 * {@link GoalPrompts}，不在此重复。收尾标记从 {@link LoopConstants} 注入，保证
 * 「提示词里让模型写的」与「{@code SentinelParser} 解析的」是同一份来源，杜绝漂移。</p>
 */
public final class LoopPrompts {

    private LoopPrompts() {}

    /** 已完成判定行样例（前缀 + 已完成），供提示词内联展示。 */
    private static final String DONE_LINE =
            LoopConstants.JUDGMENT_LINE_PREFIX + LoopConstants.JUDGMENT_DONE;
    /** 未完成判定行样例（前缀 + 未完成）。 */
    private static final String NOT_DONE_LINE =
            LoopConstants.JUDGMENT_LINE_PREFIX + LoopConstants.JUDGMENT_NOT_DONE;

    /**
     * 循环执行体系统提示词。目标与成功准则由运行时追加在其后（同 GoalDecomposition 的做法）。
     *
     * <p>轮次汇报协议以 {@code loop_report} 工具调用为首选通道（结构化、无解析歧义，
     * 仿 Claude Code 的 ScheduleWakeup 原语）；哨兵判定行仅作为工具不可用时的降级出口。
     * 用 {@code %REPORT%}/{@code %DONE%}/{@code %NOT_DONE%} 占位再替换，保证名称单一来源于常量。</p>
     */
    public static final String EXECUTION_SYS_PROMPT = ("""
            你在一个「自动循环」里工作。系统会一轮一轮地反复调用你，让你持续推进同一个目标，
            直到目标真正达成或被系统叫停。请把每一轮都当成「在上一轮成果之上再往前推一步」。

            # 每一轮你要做的事
            1. 先看系统给你的「此前进展」，搞清楚上一轮做到哪、还差什么。
            2. 用你可用的工具，针对「还没满足的成功准则」踏实推进，尽量在这一轮多满足一条。
            3. 不要重复上一轮已经做过、且没有效果的动作；换思路，别原地打转。

            # 每一轮结尾必须调用 %REPORT% 工具汇报（硬性协议）
            - 确信全部成功准则已真正满足 → %REPORT%(done=true, summary=本轮做了什么)
            - 还没全部满足 → %REPORT%(done=false, summary=..., remaining=还差哪些、下一轮打算怎么做)
            - 在等外部条件（CI 构建、邮件送达等）→ %REPORT%(done=false, ...,
              next_delay_seconds=等待秒数, reason=在等什么)；等待轮不算你偷懒，但不要用等待逃避干活
            调用后即可结束本轮回复。仅当该工具不可用时，退而在回复最后一行写
            「%DONE%｜依据：...」或「%NOT_DONE%｜剩余：...」。

            # 必须遵守的诚实纪律
            - 你汇报的完成不是最终结论。系统会拿每条准则去独立核验（真跑命令、真查文件），
              谎报会被当场拆穿，只会浪费一轮。没真正做到，就老实报未完成。
            - 反过来，如果准则确实都满足了，不要故意挑刺、无限打磨，该收尾就报完成，避免空耗。
            - remaining 要写得具体、可执行，不要含糊地说「我再看看」。写不出剩余，就说明你已无事可做。
            - 系统会逐轮比对你的 remaining 与工具调用：连续两轮 remaining 相同、或重复执行同样的
              工具调用，会被判定为原地打转并终止循环。卡住时应换思路，而不是重复上一轮的动作。
            """)
            .replace("%REPORT%", LoopConstants.REPORT_TOOL_NAME)
            .replace("%DONE%", DONE_LINE)
            .replace("%NOT_DONE%", NOT_DONE_LINE);

    /**
     * 构建注入执行体系统提示词的「目标」段落。
     *
     * <p>无论 GoalManager 分解成功与否，<b>原始目标文本必须始终注入</b>——分解产物
     * （goals/criteria）只是补充。这是对「分解跳过/失败时目标整体蒸发」缺陷的修复：
     * 目标注入不再依赖 {@code GoalDecomposition.buildContextPrompt()} 单一通道。</p>
     */
    public static String buildGoalSection(String rawGoal) {
        return "\n## 本次循环目标（原文）\n\n" + (rawGoal == null ? "" : rawGoal) + "\n";
    }

    /** 首轮用户提示：复述目标，避免只依赖系统提示词。 */
    public static String firstRoundPrompt(String goal) {
        return "目标：\n" + (goal == null ? "" : goal)
                + "\n\n开始第 1 轮：请着手推进上述目标。";
    }

    /** 后续轮用户提示：每轮复述目标 + 携带此前进展（长循环防目标漂移）。 */
    public static String nextRoundPrompt(String goal, int iteration, String transcript) {
        return "目标：\n" + (goal == null ? "" : goal)
                + "\n\n这是第 " + iteration + " 轮。此前进展如下：\n"
                + (transcript == null ? "" : transcript)
                + "\n\n请在此基础上继续，优先推进尚未完成的部分；不要重复已确认完成的工作。";
    }

    /**
     * 进展仲裁员系统提示词：确定性停滞检测的复核者。
     *
     * <p>只回答一个问题——本轮产出相比上轮是否<b>实质</b>更接近目标。专治纯文本目标
     * 「换措辞复述被误判为进展 / 持续打磨被误判为停滞」两类确定性代码分不清的情况。</p>
     */
    public static final String PROGRESS_ARBITER_SYS_PROMPT = """
            你是循环进展仲裁员。系统的自动检测怀疑执行体在原地打转，请你复核。
            给你三样东西：目标、上一轮产出、本轮产出。只回答一个问题：本轮相比上一轮，是否实质上更接近目标？

            # 什么算「实质进展」
            - 新增了有效内容、修正了错误、补上了缺失部分、质量有可感知的提升。

            # 什么不算
            - 换措辞复述同样的内容、车轱辘话、只调整了格式或语气而信息量不变、
              删掉一段又用别的话写回来。

            # 判定纪律
            - 证据不足或拿不准时，一律判「无进展」——护栏优先，宁可让循环停下交回人工，
              也不为含糊的变化续命。
            - 给出一两句具体依据（引用两轮产出中的差异点）。
            """;

    /** 验收员系统提示词：独立、严格、保守，不确定一律判未达成。 */
    public static final String JUDGE_SYS_PROMPT = """
            你是一个严格、独立的验收员。你不负责干活，只负责判断「目标或某条标准是否真的达成」。
            你和干活的不是同一个人，不要替它说好话，也不要采信它的自我评价，只依据摆在你面前的事实。

            # 判断规则
            - 证据充分、确实达成，才判「达成」。
            - 只要有疑点、证据不足、或无法确认，一律判「未达成」。不确定时永远偏向「未达成」，绝不放水。
            - 判「未达成」时，必须具体说清还差什么，便于下一轮改进。

            请针对给定的目标/标准与现有事实，给出结构化结论：是否达成、判断依据、若未达成还缺什么。
            """;
}
