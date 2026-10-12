package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.ToolApprovalChallenge;

public final class ToolApprovalRequiredException extends RuntimeException {
    private final ToolApprovalChallenge challenge;

    public ToolApprovalRequiredException(String toolName, JsonNode arguments, String fingerprint) {
        this(toolName, arguments, fingerprint, "CONFIRM", "Approve tool " + toolName);
    }

    public ToolApprovalRequiredException(
            String toolName,
            JsonNode arguments,
            String fingerprint,
            String kind,
            String description) {
        this(toolName, arguments, fingerprint, kind, description, false);
    }

    public ToolApprovalRequiredException(
            String toolName,
            JsonNode arguments,
            String fingerprint,
            String kind,
            String description,
            boolean trustedContextRead) {
        super("human approval required for tool " + toolName);
        this.challenge = new ToolApprovalChallenge(
                toolName, arguments, fingerprint, kind, description, trustedContextRead);
    }

    public String toolName() { return challenge.tool(); }
    public JsonNode arguments() { return challenge.arguments(); }
    public String fingerprint() { return challenge.fingerprint(); }
    public ToolApprovalChallenge challenge() { return challenge; }
}
