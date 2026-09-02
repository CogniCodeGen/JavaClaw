package com.javaclaw.nativehost.sandbox;

import java.util.Locale;

/** 只选择能够强制执行当前策略的平台 backend，不提供无沙箱回退。 */
final class PlatformSandboxCommandBuilder {
    private PlatformSandboxCommandBuilder() {}

    static SandboxCommandBuilder current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return new MacSandboxCommandBuilder();
        }
        if (os.contains("linux")) {
            return new LinuxSandboxCommandBuilder();
        }
        if (os.contains("windows")) {
            return new WindowsSandboxCommandBuilder();
        }
        throw new UnsupportedOperationException("no audited sandbox backend is available for: " + os);
    }
}
