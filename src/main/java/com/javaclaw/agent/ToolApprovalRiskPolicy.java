package com.javaclaw.agent;

import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.framework.spi.ToolApprovalDecision;
import com.javaclaw.framework.spi.ToolDescriptor;

import java.util.Objects;

/** Side-effect-free translation from product review settings to framework approval metadata. */
public final class ToolApprovalRiskPolicy {
    private ToolApprovalRiskPolicy() { }

    public static Assessment assess(
            ToolDescriptor tool, boolean confirmationEnabled, ToolReviewMode reviewMode) {
        Objects.requireNonNull(tool, "tool");
        if (ToolRiskRegistry.isKnownHostTool(tool.name())) {
            return new Assessment(ToolApprovalDecision.DENY, "CONFIRM");
        }
        return new Assessment(ToolApprovalDecision.DENY, "CONFIRM");
    }

    /** Risk treatment after the Spring boundary has verified the exact host implementation. */
    public static Assessment assessVerifiedHostContract(
            ToolDescriptor tool, boolean confirmationEnabled, ToolReviewMode reviewMode) {
        if (!ToolRiskRegistry.matchesHostContract(tool)) {
            return new Assessment(ToolApprovalDecision.DENY, "CONFIRM");
        }
        // Desktop session tools recheck the saved switch and OS permissions at execution.
        if ("desktop-session".equals(tool.group())
                && ToolRiskRegistry.isDesktopSessionTool(tool.name())) {
            return new Assessment(ToolApprovalDecision.ALLOW, "CONFIRM");
        }
        if (ToolRiskRegistry.isKnownHostReadOnly(tool.name())) {
            return new Assessment(ToolApprovalDecision.ALLOW, "CONFIRM");
        }
        if (ToolRiskRegistry.levelOf(tool.name()) == null) {
            return new Assessment(ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL, "CONFIRM");
        }
        return assess(tool.name(), confirmationEnabled, reviewMode);
    }

    public static Assessment assess(
            String toolName, boolean confirmationEnabled, ToolReviewMode reviewMode) {
        String checkedName = Objects.requireNonNull(toolName, "toolName");
        ToolReviewMode checkedMode = Objects.requireNonNull(reviewMode, "reviewMode");
        ToolRiskLevel level = ToolRiskRegistry.levelOf(checkedName);
        if (level == null) return new Assessment(ToolApprovalDecision.DENY, "CONFIRM");
        if (!confirmationEnabled || checkedMode == ToolReviewMode.AUTO) {
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
