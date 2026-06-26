package com.javaclaw.task.sdd.gate;

import com.javaclaw.api.interaction.ConfirmKind;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.task.sdd.ReviewGate;
import com.javaclaw.task.sdd.TaskContext;
import com.javaclaw.task.sdd.spec.Capability;
import com.javaclaw.task.sdd.spec.OpenSpecChange;
import com.javaclaw.task.sdd.spec.Proposal;

import java.util.stream.Collectors;

/**
 * 经 {@link UserInteractionPort} 的两道人机评审闸门 —— OpenSpec"先评审后实现"的落地。
 *
 * <p>取代 v5 三个 bespoke user-gate：第一道展示提案、第二道展示规格+计划，由用户放行或驳回。
 * 当前用 {@code confirm} 二态语义（通过/驳回）；驳回反馈用通用提示（富文本反馈对话框待 UI 重设计
 * 补强）。端口不可用时（无头）默认放行，避免卡死。</p>
 *
 * @author JavaClaw
 */
public final class PortReviewGate implements ReviewGate {

    private final UserInteractionPort port;
    private final int timeoutSeconds;

    public PortReviewGate(UserInteractionPort port) {
        this(port, 600);
    }

    public PortReviewGate(UserInteractionPort port, int timeoutSeconds) {
        this.port = port;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public Decision reviewProposal(TaskContext ctx, Proposal p) {
        if (port == null || !port.isAvailable()) return Decision.approve();
        String desc = "【提案评审】" + ctx.title() + "\n\n"
                + "为什么：\n" + p.why() + "\n\n"
                + "改什么：\n" + p.whatChanges() + "\n\n"
                + "不改什么：\n" + (p.outOfScope() == null || p.outOfScope().isBlank() ? "（无）" : p.outOfScope())
                + "\n\n确认 = 进入规格阶段；取消 = 驳回并按需重写提案。";
        boolean ok = port.confirm(new ConfirmRequest(
                "评审·提案", "计划评审", desc, ConfirmKind.CONFIRM, timeoutSeconds, "", true));
        return ok ? Decision.approve() : Decision.reject("用户驳回提案，请重新理解需求、调整范围后再提。");
    }

    @Override
    public Decision reviewPlan(TaskContext ctx, OpenSpecChange change) {
        if (port == null || !port.isAvailable()) return Decision.approve();
        StringBuilder sb = new StringBuilder("【规格 + 计划评审】").append(ctx.title()).append("\n\n");
        sb.append("验收场景：\n");
        for (Capability cap : change.capabilities()) {
            for (var sc : cap.allScenarios()) {
                sb.append("  · [").append(cap.name()).append("] ").append(sc.title())
                        .append("（").append(sc.criterion() == null ? "" : sc.criterion().normalizedType()).append("）\n");
            }
        }
        sb.append("\n实现计划（").append(change.tasks().size()).append(" 项）：\n");
        sb.append(change.tasks().stream()
                .map(t -> "  " + t.index() + ". " + t.action())
                .collect(Collectors.joining("\n")));
        sb.append("\n\n确认 = 开始执行；取消 = 驳回并重新规划。");
        boolean ok = port.confirm(new ConfirmRequest(
                "评审·计划", "计划评审", sb.toString(), ConfirmKind.CONFIRM, timeoutSeconds, "", true));
        return ok ? Decision.approve() : Decision.reject("用户驳回计划，请重新规划实现步骤。");
    }
}
