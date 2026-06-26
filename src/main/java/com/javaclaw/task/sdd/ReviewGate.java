package com.javaclaw.task.sdd;

import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;

/**
 * OpenSpec 的两道人机评审闸门 —— 取代 v5 三个 bespoke user-gate。
 *
 * <p>"先评审后实现"是 OpenSpec 的固有节律：确认提案、确认规格+计划。编排器在这两点
 * 阻塞征求用户决定；B4/B5 用 {@code UserInteractionPort} 实现具体对话框。</p>
 *
 * @author JavaClaw
 */
public interface ReviewGate {

    /** 第一道：评审提案（为什么做 + 改什么）。 */
    Decision reviewProposal(TaskContext ctx, Proposal proposal);

    /** 第二道：评审规格 + 实现计划（场景验收标准 + tasks.md 清单）。 */
    Decision reviewPlan(TaskContext ctx, OpenSpecChange change);

    /**
     * 评审结论。
     *
     * @param approved true=通过进入下一阶段；false=驳回，按 feedback 返工
     * @param feedback 驳回时的修改意见（通过时可空）
     */
    record Decision(boolean approved, String feedback) {
        public static Decision approve() {
            return new Decision(true, null);
        }
        public static Decision reject(String feedback) {
            return new Decision(false, feedback);
        }
    }
}
