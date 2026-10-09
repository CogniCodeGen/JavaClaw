package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopAvailability;
import com.javaclaw.desktop.api.DesktopInputPolicy;
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
    void strictRequiresPublicSemanticButDoesNotRequirePostEvent() {
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
                new DesktopAvailability(true, "macos", capture | accessibility | DesktopAvailability.PUBLIC_SEMANTIC, ""))
                .available());
    }

    @Test
    void captureAndInputAreRequiredEvenIfNativeProbeClaimsAvailability() {
        assertFalse(NativeDesktopSystemPermissionService.requireCaptureAndInput(
                new DesktopAvailability(true, "macos", DesktopAvailability.SEMANTIC_INPUT, ""))
                .available());
        assertTrue(NativeDesktopSystemPermissionService.requireCaptureAndInput(
                new DesktopAvailability(true, "windows", DesktopAvailability.CAPTURE
                        | DesktopAvailability.FOREGROUND_INPUT, ""), DesktopInputPolicy.SYSTEM_EXPLICIT)
                .available());
    }

    @Test
    void unsupportedSystemDoesNotLoadSystemApiOrPrompt() throws Exception {
        var service = new NativeDesktopSystemPermissionService(ApplicationHome.at(temp), "Linux");
        assertFalse(service.status().available());
        assertFalse(service.requestPermissions().available());
    }

    @Test
    void systemApiFailureIsNotReportedAsThreeMissingPermissions() throws Exception {
        String otherOs = System.getProperty("os.name", "").startsWith("Windows") ? "Mac OS X" : "Windows";
        var service = new NativeDesktopSystemPermissionService(ApplicationHome.at(temp), otherOs);
        DesktopAvailability result = service.status();
        assertFalse(result.available());
        assertEquals("", result.providerId());
        assertFalse(result.detail().isBlank());
    }
}
