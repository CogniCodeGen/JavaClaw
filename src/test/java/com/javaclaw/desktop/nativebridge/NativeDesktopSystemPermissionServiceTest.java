package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.platform.data.ApplicationHome;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeDesktopSystemPermissionServiceTest {
    @TempDir Path temp;

    @Test
    void captureAloneDoesNotEnableComputerAccess() {
        DesktopAvailability captureOnly = new DesktopAvailability(true, "macos",
                DesktopAvailability.CAPTURE, "Accessibility permission is required");
        DesktopAvailability result = NativeDesktopSystemPermissionService
                .requireCaptureAndInput(captureOnly);
        assertFalse(result.available());
        assertTrue(result.detail().contains("Accessibility"));
        assertTrue(NativeDesktopSystemPermissionService.requireCapture(captureOnly).available(),
                "read-only desktop sessions only need capture capability");
    }

    @Test
    void macosRequiresCaptureAccessibilityAndPostEventForFullComputerAccess() {
        int capture = DesktopAvailability.CAPTURE;
        int accessibility = DesktopAvailability.SEMANTIC_INPUT;
        int foreground = DesktopAvailability.FOREGROUND_INPUT;
        assertFalse(NativeDesktopSystemPermissionService.requireCaptureAndInput(
                new DesktopAvailability(true, "macos", capture | accessibility,
                        "缺少输入事件发送（Post Event）"))
                .available());
        assertFalse(NativeDesktopSystemPermissionService.requireCaptureAndInput(
                new DesktopAvailability(true, "macos", capture | foreground,
                        "缺少辅助功能"))
                .available());
        assertTrue(NativeDesktopSystemPermissionService.requireCaptureAndInput(
                new DesktopAvailability(true, "macos", capture | accessibility | foreground, ""))
                .available());
    }

    @Test
    void captureAndInputAreRequiredEvenIfNativeProbeClaimsAvailability() {
        assertFalse(NativeDesktopSystemPermissionService.requireCaptureAndInput(
                new DesktopAvailability(true, "macos", DesktopAvailability.SEMANTIC_INPUT, ""))
                .available());
        assertTrue(NativeDesktopSystemPermissionService.requireCaptureAndInput(
                new DesktopAvailability(true, "windows", DesktopAvailability.CAPTURE
                        | DesktopAvailability.FOREGROUND_INPUT, ""))
                .available());
    }

    @Test
    void unsupportedSystemDoesNotLoadNativeLibraryOrPrompt() throws Exception {
        var service = new NativeDesktopSystemPermissionService(ApplicationHome.at(temp), "Linux");
        assertFalse(service.status().available());
        assertFalse(service.requestPermissions().available());
    }

    @Test
    void nativeLibraryFailureIsNotReportedAsThreeMissingPermissions() throws Exception {
        var service = new NativeDesktopSystemPermissionService(ApplicationHome.at(temp), "Mac OS X");
        DesktopAvailability result = service.status();
        assertFalse(result.available());
        assertEquals("", result.providerId());
        assertFalse(result.detail().isBlank());
    }
}
