package com.javaclaw.framework.api;

import java.util.Optional;
import java.util.concurrent.Callable;

/** Scoped grant used while an approved framework tool invokes host-tool guards. */
public final class ToolApprovalScope {
    private static final ScopedValue<ToolApprovalGrant> CURRENT = ScopedValue.newInstance();

    private ToolApprovalScope() {}

    public static Optional<ToolApprovalGrant> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    public static <T> T call(ToolApprovalGrant grant, Callable<T> action) throws Exception {
        return ScopedValue.where(CURRENT, grant).call(() -> action.call());
    }
}
