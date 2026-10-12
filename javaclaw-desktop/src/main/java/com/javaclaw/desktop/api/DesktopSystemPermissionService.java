package com.javaclaw.desktop.api;

/**
 * Checks and requests the operating-system permissions needed for desktop sessions.
 * Requests may display an OS-owned prompt or open the relevant permission pane,
 * and must be called off the JavaFX thread. A request can remain unavailable
 * until the user enables access and checks again.
 */
public interface DesktopSystemPermissionService {
    DesktopAvailability status();

    DesktopAvailability requestPermissions();

    default DesktopAvailability status(DesktopInputPolicy policy) { return status(); }
    default DesktopAvailability requestPermissions(DesktopInputPolicy policy) {
        return requestPermissions();
    }
}
