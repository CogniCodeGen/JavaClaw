package com.javaclaw.desktop.api;

import java.util.List;

/** A launched application and any windows discovered for its exact process ID. */
public record DesktopApplicationLaunchResult(long processId, String applicationId, List<DesktopTarget> targets,
                                             String detail, boolean dispatchAttempted) {
    public DesktopApplicationLaunchResult {
        if (processId <= 0) throw new IllegalArgumentException("launched process ID is required");
        applicationId = applicationId == null ? "" : applicationId;
        targets = List.copyOf(targets);
        detail = detail == null ? "" : detail;
    }

    public DesktopApplicationLaunchResult(long processId, String applicationId,
            List<DesktopTarget> targets, String detail) {
        this(processId, applicationId, targets, detail, true);
    }

    public DesktopApplicationLaunchResult(long processId, List<DesktopTarget> targets, String detail) {
        this(processId, "", targets, detail, true);
    }
}
