package com.javaclaw.application.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.agent.ToolCallOrigin;
import com.javaclaw.agent.ToolConfirmationManager;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.CancelReason;
import com.javaclaw.framework.api.ResumeCommand;
import com.javaclaw.framework.api.RunHandle;
import com.javaclaw.framework.api.ToolApprovalChallenge;

import java.util.Objects;

/** Shared adapter for resolving a framework approval event through the product confirmation UI. */
public final class FrameworkToolApprovalCoordinator {
    private FrameworkToolApprovalCoordinator() {}

    public static ToolApprovalChallenge decode(JsonNode payload) {
        return ToolApprovalChallenge.fromEventPayload(payload);
    }

    public static void resolve(
            AgentClient agents,
            RunHandle handle,
            ToolCallOrigin origin,
            JsonNode payload) {
        Objects.requireNonNull(agents, "agents");
        Objects.requireNonNull(handle, "handle");
        ToolApprovalChallenge challenge;
        try {
            challenge = decode(payload);
        } catch (RuntimeException malformed) {
            agents.cancel(handle.id(), new CancelReason(
                    "TOOL_APPROVAL_EVENT_INVALID", malformed.getMessage()));
            return;
        }
        String description = challenge.description();
        if (challenge.arguments().isObject() && !challenge.arguments().isEmpty()) {
            description += "\n参数: " + challenge.arguments();
        }
        ToolConfirmationManager.ConfirmOutcome outcome =
                ToolConfirmationManager.requestConfirmationOutcome(
                        origin == null ? ToolCallOrigin.UNKNOWN : origin,
                        challenge.tool(), description);
        if (!outcome.isAllow()) {
            agents.cancel(handle.id(), new CancelReason(
                    "TOOL_APPROVAL_DENIED", "user denied " + challenge.tool()));
            return;
        }
        ObjectNode command = JsonNodeFactory.instance.objectNode();
        command.put("approved", true);
        command.put("fingerprint", challenge.fingerprint());
        command.put("humanApproved",
                outcome == ToolConfirmationManager.ConfirmOutcome.ALLOWED_HUMAN);
        try {
            agents.resume(handle.id(), new ResumeCommand("tool.approval", command));
        } catch (RuntimeException failure) {
            agents.cancel(handle.id(), new CancelReason(
                    "TOOL_APPROVAL_RESUME_FAILED", failure.toString()));
        }
    }
}
