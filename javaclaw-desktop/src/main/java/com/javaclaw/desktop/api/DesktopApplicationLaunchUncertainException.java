package com.javaclaw.desktop.api;

/** Launch may already have reached the OS; discover the process before any retry. */
public final class DesktopApplicationLaunchUncertainException extends IllegalStateException {
    private final long processId;
    private final String applicationId;

    public DesktopApplicationLaunchUncertainException(String detail, long processId) {
        this(detail, processId, "", null);
    }

    public DesktopApplicationLaunchUncertainException(String detail, long processId,
            Throwable cause) {
        this(detail, processId, "", cause);
    }

    public DesktopApplicationLaunchUncertainException(String detail, long processId,
            String applicationId, Throwable cause) {
        super(detail, cause);
        this.processId = processId;
        this.applicationId = applicationId == null ? "" : applicationId;
    }

    public long processId() { return processId; }
    public String applicationId() { return applicationId; }
}
