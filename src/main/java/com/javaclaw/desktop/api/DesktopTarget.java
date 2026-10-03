package com.javaclaw.desktop.api;

/** Opaque target identity; native window handles never cross the public API. */
public record DesktopTarget(String providerId, String id, long processId, String application,
                            String title, int x, int y, int width, int height, int flags,
                            String applicationId) {
    public static final int MINIMIZED = 1;
    public static final int VISIBLE = 2;
    public static final int POPUP = 4;
    /** Classified by the platform provider from window ownership, not its display name. */
    public static final int SYSTEM_SURFACE = 8;

    public DesktopTarget {
        if (providerId == null || providerId.isBlank() || id == null || id.isBlank())
            throw new IllegalArgumentException("target identity is required");
        application = application == null ? "" : application;
        title = title == null ? "" : title;
        applicationId = applicationId == null ? "" : applicationId;
    }

    public DesktopTarget(String providerId, String id, long processId, String application,
            String title, int x, int y, int width, int height, int flags) {
        this(providerId, id, processId, application, title, x, y, width, height, flags, "");
    }

    public boolean minimized() { return (flags & MINIMIZED) != 0; }
    public boolean visible() { return (flags & VISIBLE) != 0; }
    public boolean systemSurface() { return (flags & SYSTEM_SURFACE) != 0; }
}
