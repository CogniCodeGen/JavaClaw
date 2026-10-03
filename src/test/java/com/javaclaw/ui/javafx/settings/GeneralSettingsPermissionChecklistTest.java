package com.javaclaw.ui.javafx.settings;

import com.javaclaw.desktop.api.DesktopAvailability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralSettingsPermissionChecklistTest {
    @Test
    void postEventIsNotReportedDeniedBeforeAccessibilityCanBeChecked() {
        String beforeAccessibility = GeneralSettingsController.permissionChecklist(
                new DesktopAvailability(false, "macos", DesktopAvailability.CAPTURE, ""));
        assertTrue(beforeAccessibility.contains("辅助功能：待授权"));
        assertTrue(beforeAccessibility.contains("发送输入事件：待检查（先授权辅助功能）"));

        String afterAccessibility = GeneralSettingsController.permissionChecklist(
                new DesktopAvailability(false, "macos", DesktopAvailability.CAPTURE
                        | DesktopAvailability.SEMANTIC_INPUT, ""));
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
        assertEquals(DesktopAvailability.FOREGROUND_INPUT,
                GeneralSettingsController.firstMissingCapability(captureAndAccessibility));
        assertEquals(0, GeneralSettingsController.firstMissingCapability(
                new DesktopAvailability(false, "", 0, "需要 macOS 14")));
    }
}
