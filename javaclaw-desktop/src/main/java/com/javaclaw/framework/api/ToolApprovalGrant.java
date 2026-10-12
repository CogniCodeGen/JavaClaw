package com.javaclaw.framework.api;

import java.util.Objects;

/** One-shot authorization for exactly one challenged tool invocation. */
public record ToolApprovalGrant(
        String tool,
        String fingerprint,
        boolean approved,
        boolean humanApproved) {

    public ToolApprovalGrant {
        tool = Objects.requireNonNull(tool, "tool").trim();
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint").trim();
        if (tool.isEmpty() || fingerprint.isEmpty()) {
            throw new IllegalArgumentException("approval grant requires tool and fingerprint");
        }
        if (!approved && humanApproved) {
            throw new IllegalArgumentException("a denied grant cannot be human-approved");
        }
    }

    public static ToolApprovalGrant approve(
            ToolApprovalChallenge challenge, boolean humanApproved) {
        return new ToolApprovalGrant(challenge.tool(), challenge.fingerprint(), true, humanApproved);
    }

    public static ToolApprovalGrant deny(ToolApprovalChallenge challenge) {
        return new ToolApprovalGrant(challenge.tool(), challenge.fingerprint(), false, false);
    }
}
