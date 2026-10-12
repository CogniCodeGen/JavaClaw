package com.javaclaw.desktop.api;

/** Grant policy for observing and controlling desktop applications. */
@FunctionalInterface
public interface DesktopConsentPort {
    boolean request(DesktopSessionOwner owner, DesktopTarget target, Purpose purpose);

    /** Checked throughout a session so turning the setting off revokes existing access. */
    default boolean enabled() { return true; }

    /** Dynamic switch and operating-system permission state for an existing session. */
    default DesktopAvailability accessStatus() {
        boolean allowed = enabled();
        return new DesktopAvailability(allowed, "", 0,
                allowed ? "" : "请先在设置中开启电脑应用访问");
    }

    /** Capability-specific status. Older consent implementations keep their existing behavior. */
    default DesktopAvailability accessStatus(Purpose purpose) { return accessStatus(); }

    enum Purpose { OBSERVE, CONTROL, FOREGROUND_TAKEOVER }
}
