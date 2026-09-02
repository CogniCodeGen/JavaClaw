package com.javaclaw.nativehost.tray;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemTrayFeatureTest {
    @Test
    void 三个平台只在非Headless且SystemTray受支持时可用() {
        assertAvailable("Mac OS X", SystemTrayFeature.Platform.MACOS);
        assertAvailable("Linux", SystemTrayFeature.Platform.LINUX);
        assertAvailable("Windows 11", SystemTrayFeature.Platform.WINDOWS);

        assertFalse(SystemTrayFeature.detect("Mac OS X", true, true).available());
        assertFalse(SystemTrayFeature.detect("Linux", false, false).available());
        assertFalse(SystemTrayFeature.detect("Plan 9", false, true).available());
    }

    @Test
    void Idea直接运行不加载Awt并明确FailClosed() {
        SystemTrayFeature.Status status = SystemTrayFeature.detect();

        assertFalse(status.available());
        assertTrue(status.unavailableReason().orElseThrow().contains("IDEA"));
    }

    private static void assertAvailable(String operatingSystem, SystemTrayFeature.Platform platform) {
        SystemTrayFeature.Status status = SystemTrayFeature.detect(operatingSystem, false, true);
        assertTrue(status.available());
        assertEquals(platform, status.platform());
    }
}
