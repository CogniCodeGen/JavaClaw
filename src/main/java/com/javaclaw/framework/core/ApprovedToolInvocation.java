package com.javaclaw.framework.core;

import com.javaclaw.framework.api.ToolApprovalChallenge;
import com.javaclaw.framework.api.ToolApprovalGrant;

import java.util.Objects;

/** Exact tool invocation authorized by a durable {@code tool.approval} resume event. */
public record ApprovedToolInvocation(
        ToolApprovalChallenge challenge,
        ToolApprovalGrant grant) {

    public ApprovedToolInvocation {
        challenge = Objects.requireNonNull(challenge, "challenge");
        grant = Objects.requireNonNull(grant, "grant");
        if (!grant.approved()
                || !grant.tool().equals(challenge.tool())
                || !grant.fingerprint().equals(challenge.fingerprint())) {
            throw new IllegalArgumentException(
                    "approved invocation grant must match its challenge");
        }
    }
}
