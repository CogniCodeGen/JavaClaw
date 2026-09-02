package com.javaclaw.server.security.grant;

import java.util.Objects;

import com.javaclaw.api.PermissionDecisionTrace;

/**
 * 已原子消费额度的无人值守工具调用预留。
 *
 * @param grantId 授权标识
 * @param grantRevision 消费时的授权 revision
 * @param invocationId 稳定调用标识
 * @param trace 允许决策追踪
 */
public record UnattendedToolReservation(
        String grantId, long grantRevision, String invocationId, PermissionDecisionTrace trace) {
    /** 校验预留身份与允许追踪。 */
    public UnattendedToolReservation {
        grantId = identifier(grantId);
        if (grantRevision < 1) {
            throw new IllegalArgumentException("grantRevision must be positive");
        }
        invocationId = identifier(invocationId);
        Objects.requireNonNull(trace, "trace");
        if (!trace.allowed()
                || !trace.grantId().equals(grantId)
                || trace.steps().stream().noneMatch(step -> step.revision() == grantRevision)) {
            throw new IllegalArgumentException("reservation trace does not match grant");
        }
    }

    private static String identifier(String value) {
        String checked = Objects.requireNonNull(value, "identifier").strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException("identifier contains unsupported characters");
        }
        return checked;
    }
}
