package com.javaclaw.desktop.ffm.macos;

/** An in-process AX request synchronously reenters the host UI toolkit through native code. */
final class MacTargetPolicy {
    private static final long CURRENT_PID = ProcessHandle.current().pid();
    private MacTargetPolicy() { }

    static boolean externalProcess(long pid) { return externalProcess(pid, CURRENT_PID); }

    static boolean externalProcess(long pid, long currentPid) {
        return pid > 0 && pid <= Integer.MAX_VALUE && pid != currentPid;
    }

    static void requireExternalProcess(long pid) {
        if (!externalProcess(pid))
            throw new SecurityException("macOS desktop targets must belong to another application process; self Accessibility access is prohibited");
    }
}
