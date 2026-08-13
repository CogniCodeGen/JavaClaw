package com.javaclaw.agent;

import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.framework.spi.ToolApprovalDecision;

import java.util.Objects;

/** Side-effect-free translation from product review settings to framework approval metadata. */
public final class ToolApprovalRiskPolicy {
    private ToolApprovalRiskPolicy() { }

    public static Assessment assess(
            String toolName, boolean confirmationEnabled, ToolReviewMode reviewMode) {
        String checkedName = Objects.requireNonNull(toolName, "toolName");
        ToolReviewMode checkedMode = Objects.requireNonNull(reviewMode, "reviewMode");
        ToolRiskLevel level = ToolRiskRegistry.levelOf(checkedName);
        if (!confirmationEnabled || level == null || checkedMode == ToolReviewMode.AUTO) {
            return new Assessment(ToolApprovalDecision.ALLOW, "CONFIRM");
        }
        ToolRiskLevel effective = checkedMode == ToolReviewMode.MANUAL
                && level == ToolRiskLevel.NOTIFY ? ToolRiskLevel.CONFIRM : level;
        String kind = effective == ToolRiskLevel.DOUBLE_CONFIRM
                ? "DOUBLE_CONFIRM" : "CONFIRM";
        return new Assessment(ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL, kind);
    }

    public record Assessment(ToolApprovalDecision decision, String kind) {
        public Assessment {
            decision = Objects.requireNonNull(decision, "decision");
            kind = Objects.requireNonNull(kind, "kind");
        }
    }
}
