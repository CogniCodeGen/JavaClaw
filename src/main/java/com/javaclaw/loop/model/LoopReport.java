package com.javaclaw.loop.model;

/**
 * 执行体的结构化轮次汇报（经 {@code loop_report} 工具提交，仿 Claude Code 的
 * ScheduleWakeup 原语：工具调用没有文本解析歧义，取代哨兵行成为首选信号通道）。
 *
 * @param done             目标是否已全部达成（是否采信仍由核验决定，提议权在模型、裁决权在代码）
 * @param summary          本轮做了什么的简述（供接力上下文与状态卡展示）
 * @param remaining        还差什么、下一轮打算怎么做（进展判定比对「剩余清单」用；完成时可空）
 * @param nextDelaySeconds 建议的下轮延迟秒数：0=立即；>0=声明式等待（如「等 CI 跑完再查」），
 *                         等待轮不计入停滞（轮询语义），代码会按上限截断
 * @param reason           等待原因（nextDelaySeconds>0 时向用户解释在等什么）
 */
public record LoopReport(boolean done, String summary, String remaining,
                         long nextDelaySeconds, String reason) {

    public LoopReport {
        if (summary == null) summary = "";
        if (remaining == null) remaining = "";
        if (reason == null) reason = "";
        if (nextDelaySeconds < 0) nextDelaySeconds = 0;
    }

    /** 是否声明式等待轮（模型明示在等外部条件，本轮的重复行动不算空转）。 */
    public boolean isWaitRound() {
        return !done && nextDelaySeconds > 0;
    }
}
