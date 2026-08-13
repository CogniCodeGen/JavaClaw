package com.javaclaw.framework.api;

import java.util.Objects;

/** Auditable cancellation reason. */
public record CancelReason(String code, String detail) {
    public CancelReason {
        code = Objects.requireNonNull(code, "code").trim();
        detail = detail == null ? "" : detail.trim();
        if (code.isEmpty()) {
            throw new IllegalArgumentException("cancel code must not be blank");
        }
    }

    public static CancelReason requestedByUser() {
        return new CancelReason("USER_REQUEST", "");
    }
}
