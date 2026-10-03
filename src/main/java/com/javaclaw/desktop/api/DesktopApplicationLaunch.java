package com.javaclaw.desktop.api;

/** Result of one platform launch request, before looking for a window. */
public record DesktopApplicationLaunch(long processId, String applicationId, String detail) {
    public DesktopApplicationLaunch {
        if (processId <= 0) throw new IllegalArgumentException("launched process ID is required");
        applicationId = applicationId == null ? "" : applicationId;
        detail = detail == null ? "" : detail;
    }

    public DesktopApplicationLaunch(long processId, String detail) {
        this(processId, "", detail);
    }
}
