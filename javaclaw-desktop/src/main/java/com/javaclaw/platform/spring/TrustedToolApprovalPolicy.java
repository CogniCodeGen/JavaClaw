package com.javaclaw.platform.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.agent.ToolApprovalRiskPolicy;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.agent.ToolRiskRegistry;
import com.javaclaw.config.AgentConfig;
import com.javaclaw.config.ToolReviewMode;
import com.javaclaw.framework.api.RunRequest;
import com.javaclaw.framework.builtin.SubAgentTools;
import com.javaclaw.framework.builtin.memory.MemoryRecallExtension;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.ToolApprovalDecision;
import com.javaclaw.framework.spi.ToolApprovalPolicy;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.springai.SpringAiAnnotatedToolRegistry;
import com.javaclaw.framework.springai.TrustedFrameworkToolIdentity;

import java.util.Objects;

/** Connects host implementation identity to the product's descriptor risk rules. */
final class TrustedToolApprovalPolicy implements ToolApprovalPolicy {
    private final AgentConfig settings;

    TrustedToolApprovalPolicy(AgentConfig settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override public ToolApprovalDecision evaluate(
            ToolDescriptor tool, JsonNode arguments, RunRequest request) {
        return ToolApprovalRiskPolicy.assess(tool, ToolConfirmationManager.isEnabled(),
                settings.getToolReviewMode()).decision();
    }

    @Override public ToolApprovalDecision evaluate(
            FrameworkTool tool, JsonNode arguments, RunRequest request) {
        ToolApprovalDecision decision = assess(tool, ToolConfirmationManager.isEnabled(),
                settings.getToolReviewMode()).decision();
        if (decision != ToolApprovalDecision.DENY && requiresInteractionSessionApproval(tool, request))
            return ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL;
        return decision;
    }

    @Override public String approvalKind(
            ToolDescriptor tool, JsonNode arguments, RunRequest request) {
        return ToolApprovalRiskPolicy.assess(tool, ToolConfirmationManager.isEnabled(),
                settings.getToolReviewMode()).kind();
    }

    @Override public String approvalKind(
            FrameworkTool tool, JsonNode arguments, RunRequest request) {
        if (requiresInteractionSessionApproval(tool, request)) return "CONFIRM";
        return assess(tool, ToolConfirmationManager.isEnabled(), settings.getToolReviewMode()).kind();
    }

    private static boolean requiresInteractionSessionApproval(FrameworkTool tool, RunRequest request) {
        return com.javaclaw.framework.core.InteractionExecutionPolicy.isInteraction(request)
                && tool.descriptor().name().equals("site_save_session")
                && SpringAiAnnotatedToolRegistry.isExactHostTool(tool);
    }

    static ToolApprovalRiskPolicy.Assessment assess(
            FrameworkTool tool, boolean confirmationEnabled, ToolReviewMode reviewMode) {
        Objects.requireNonNull(tool, "tool");
        if (com.javaclaw.framework.builtin.InteractionTools.isTrustedTool(tool)) {
            // Delegation only narrows permissions. Actual browser/native actions keep their own approvals.
            return new ToolApprovalRiskPolicy.Assessment(ToolApprovalDecision.ALLOW, "CONFIRM");
        }
        if (TrustedFrameworkToolIdentity.isContextRead(tool)
                || TrustedFrameworkToolIdentity.isToolCatalog(tool)
                || MemoryRecallExtension.isTrustedRecallTool(tool)
                || SubAgentTools.isTrustedResultTool(tool)) {
            return new ToolApprovalRiskPolicy.Assessment(ToolApprovalDecision.ALLOW, "CONFIRM");
        }
        ToolDescriptor descriptor = tool.descriptor();
        if (ToolRiskRegistry.isKnownHostTool(descriptor.name())) {
            if (!SpringAiAnnotatedToolRegistry.isExactHostTool(tool)) {
                return new ToolApprovalRiskPolicy.Assessment(ToolApprovalDecision.DENY, "CONFIRM");
            }
            return ToolApprovalRiskPolicy.assessVerifiedHostContract(
                    descriptor, confirmationEnabled, reviewMode);
        }
        if (SubAgentTools.isTrustedTool(tool)) {
            return new ToolApprovalRiskPolicy.Assessment(
                    ToolApprovalDecision.REQUIRE_HUMAN_APPROVAL, "CONFIRM");
        }
        return ToolApprovalRiskPolicy.assess(descriptor, confirmationEnabled, reviewMode);
    }
}
