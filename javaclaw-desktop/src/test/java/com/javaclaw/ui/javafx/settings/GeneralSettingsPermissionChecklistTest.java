package com.javaclaw.ui.javafx.settings;

import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopInputPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralSettingsPermissionChecklistTest {
    @Test
    void postEventIsNotReportedDeniedBeforeAccessibilityCanBeChecked() {
        String beforeAccessibility = DesktopPermissionStatusText.permissionChecklist(
                new DesktopAvailability(false, "macos", DesktopAvailability.CAPTURE, ""), DesktopInputPolicy.SYSTEM_EXPLICIT);
        assertTrue(beforeAccessibility.contains("辅助功能：待授权"));
        assertTrue(beforeAccessibility.contains("发送输入事件：待检查（先授权辅助功能）"));

        String afterAccessibility = DesktopPermissionStatusText.permissionChecklist(
                new DesktopAvailability(false, "macos", DesktopAvailability.CAPTURE
                        | DesktopAvailability.SEMANTIC_INPUT, ""), DesktopInputPolicy.SYSTEM_EXPLICIT);
        assertTrue(afterAccessibility.contains("发送输入事件：待授权"));
    }

    @Test
    void permissionStagesAdvanceOnlyAfterTheRequestedGrant() {
        DesktopAvailability none = new DesktopAvailability(false, "macos", 0, "");
        DesktopAvailability capture = new DesktopAvailability(false, "macos",
                DesktopAvailability.CAPTURE, "");
        DesktopAvailability captureAndAccessibility = new DesktopAvailability(false, "macos",
                DesktopAvailability.CAPTURE | DesktopAvailability.SEMANTIC_INPUT, "");

        assertEquals(DesktopAvailability.CAPTURE,
                GeneralSettingsController.firstMissingCapability(none));
        assertFalse(GeneralSettingsController.promptedPermissionWasGranted(
                DesktopAvailability.CAPTURE, none));
        assertTrue(GeneralSettingsController.promptedPermissionWasGranted(
                DesktopAvailability.CAPTURE, capture));
        assertEquals(DesktopAvailability.SEMANTIC_INPUT,
                GeneralSettingsController.firstMissingCapability(capture));
        assertEquals(0, GeneralSettingsController.firstMissingCapability(captureAndAccessibility));
        assertEquals(DesktopAvailability.FOREGROUND_INPUT,
                DesktopPermissionStatusText.firstMissingCapability(captureAndAccessibility,
                        DesktopInputPolicy.SYSTEM_EXPLICIT));
        assertFalse(GeneralSettingsController.permissionChecklist(captureAndAccessibility)
                .contains("发送输入事件"));
        assertEquals(0, GeneralSettingsController.firstMissingCapability(
                new DesktopAvailability(false, "", 0, "需要 macOS 14")));
    }
}
