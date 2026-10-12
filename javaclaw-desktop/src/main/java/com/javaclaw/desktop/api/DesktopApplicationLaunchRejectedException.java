package com.javaclaw.desktop.api;

import java.util.Objects;

/** Trusted platform/service rejection made before any application launch was dispatched. */
public final class DesktopApplicationLaunchRejectedException extends IllegalStateException {
    public enum Reason {
        APPLICATION_NOT_FOUND, AMBIGUOUS_APPLICATION, INVALID_ARGUMENTS,
        ACCESS_DENIED, UNSUPPORTED, SERVICE_UNAVAILABLE, SCOPE_CLOSED, PRE_DISPATCH_FAILURE
    }

    private final Reason reason;
    private final int nativeCode;

    public DesktopApplicationLaunchRejectedException(Reason reason, String detail) {
        this(reason, 0, detail, null);
    }

    public DesktopApplicationLaunchRejectedException(Reason reason, int nativeCode,
            String detail, Throwable cause) {
        super(detail, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.nativeCode = nativeCode;
    }

    public Reason reason() { return reason; }
    public String reasonCode() { return reason.name(); }
    /** Zero denotes a Java admission check rather than a native result code. */
    public int nativeCode() { return nativeCode; }
    public boolean dispatchAttempted() { return false; }
}
