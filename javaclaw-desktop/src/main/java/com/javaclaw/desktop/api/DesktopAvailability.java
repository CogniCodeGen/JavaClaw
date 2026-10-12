package com.javaclaw.desktop.api;

/** Stable Java capability bits; unavailable providers include a reason. */
public record DesktopAvailability(boolean available, String providerId, int capabilities,
                                  String detail) {
    public static final int CAPTURE = 1;
    public static final int SEMANTIC_INPUT = 2;
    public static final int DIRECTED_INPUT = 4;
    public static final int FOREGROUND_INPUT = 8;
    /** The platform implementation attests that background input uses public semantic APIs only. */
    public static final int PUBLIC_SEMANTIC = 16;

    public DesktopAvailability {
        providerId = providerId == null ? "" : providerId;
        detail = detail == null ? "" : detail;
    }
}
