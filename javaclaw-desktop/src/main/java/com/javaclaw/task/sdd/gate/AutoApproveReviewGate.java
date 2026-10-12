package com.javaclaw.task.sdd.gate;

import com.javaclaw.task.sdd.ReviewGate;
import com.javaclaw.task.sdd.TaskContext;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;

/**
 * 全自动放行的评审闸门 —— 用于无头/自动运行（定时任务、自测、CI）场景，两道评审一律通过。
 *
 * @author JavaClaw
 */
public final class AutoApproveReviewGate implements ReviewGate {

    @Override
    public Decision reviewProposal(TaskContext ctx, Proposal proposal) {
        return Decision.approve();
    }

    @Override
    public Decision reviewPlan(TaskContext ctx, OpenSpecChange change) {
        return Decision.approve();
    }
}
