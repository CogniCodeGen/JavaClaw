package com.javaclaw.desktop.ffm.windows;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopFrameGeometry;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowsBindingsTest {
    @Test void windowsGuidsUseMixedEndianAbiAndWideStringsRetainSupplementaryCharacters() {
        try (Arena arena = Arena.ofConfined()) {
            var guid = Win32.guid(arena, "3628e81b-3cac-4c60-b7f4-23ce0e0c3356");
            assertArrayEquals(new byte[]{0x1b, (byte) 0xe8, 0x28, 0x36, (byte) 0xac, 0x3c, 0x60, 0x4c,
                    (byte) 0xb7, (byte) 0xf4, 0x23, (byte) 0xce, 0x0e, 0x0c, 0x33, 0x56}, guid.toArray(ValueLayout.JAVA_BYTE));
            assertEquals("中😀", Win32.wideString(Win32.wide(arena, "中😀"), 4));
        }
    }

    @Test void keyChordsRejectAmbiguousOrIncompleteSequencesBeforeAnySystemCall() {
        assertEquals(List.of(0x11, 0x10, 0x41), WindowsInput.chord("Ctrl+Shift+A"));
        assertEquals(List.of(0x7b), WindowsInput.chord("F12"));
        for (String chord : List.of("", "Ctrl+", "+A", "A+Ctrl", "CTRL+CONTROL+A", "A+B", "F13", "F1garbage", "Ctrl", "٢"))
            assertEquals(List.of(), WindowsInput.chord(chord), chord);
        assertTrue(WindowsInput.validUnicode("A😀中"));
        assertFalse(WindowsInput.validUnicode("\uD83D"));
        assertFalse(WindowsInput.validUnicode("\uDE00"));
        assertFalse(WindowsInput.validUnicode("\0"));
    }

    @Test void inputUnionUsesWindows64BitOffsetsAndPreservesUnicodeAndWheelDirection() {
        try (Arena arena = Arena.ofConfined()) {
            var input = arena.allocate(40, 8);
            WindowsInput.encode(input, WindowsInput.Event.key(0, '中', 4));
            assertEquals(1, input.get(ValueLayout.JAVA_INT, 0));
            assertEquals(0, input.get(ValueLayout.JAVA_SHORT, 8));
            assertEquals('中', (char) input.get(ValueLayout.JAVA_SHORT, 10));
            assertEquals(4, input.get(ValueLayout.JAVA_INT, 12));
            assertEquals(0, input.get(ValueLayout.JAVA_LONG, 24));
            WindowsInput.encode(input, WindowsInput.Event.mouse(0x800, -120, 0, 0));
            assertEquals(0, input.get(ValueLayout.JAVA_INT, 0));
            assertEquals(-120, input.get(ValueLayout.JAVA_INT, 16));
            assertEquals(0x800, input.get(ValueLayout.JAVA_INT, 20));
            assertEquals(0, input.get(ValueLayout.JAVA_LONG, 32));
        }
    }

    @Test void captureAllocationIsBoundedBeforeCreatingTexturesOrJavaArrays() {
        WindowsCapture.validateSize(3840, 2160);
        assertThrows(IllegalStateException.class, () -> WindowsCapture.validateSize(0, 100));
        assertThrows(IllegalStateException.class, () -> WindowsCapture.validateSize(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(IllegalStateException.class, () -> WindowsCapture.validateSize(16384, 16384));
    }

    @Test void installedApplicationRequestsExcludePathsArgumentsAndOverlongUtf8Identities() {
        assertTrue(WindowsApplications.validName("微信"));
        assertTrue(WindowsApplications.validName("Notepad.exe"));
        for (String name : List.of("", " test", "test ", "C:\\app.exe", "../app", "folder/app", "app\n", "中".repeat(86)))
            assertFalse(WindowsApplications.validName(name), name);
    }

    @Test void backgroundProtectsEveryWindowOfTheActiveProcessWhileForegroundRequiresTheExactTarget() {
        assertFalse(WindowsSession.inputWindowAllowed(41, 101, 102, 41, false));
        assertFalse(WindowsSession.inputWindowAllowed(41, 101, 101, 41, false));
        assertFalse(WindowsSession.inputWindowAllowed(41, 101, 0, 0, false));
        assertTrue(WindowsSession.inputWindowAllowed(41, 101, 201, 42, false));
        assertFalse(WindowsSession.inputWindowAllowed(41, 101, 102, 41, true));
        assertFalse(WindowsSession.inputWindowAllowed(41, 101, 101, 42, true));
        assertTrue(WindowsSession.inputWindowAllowed(41, 101, 101, 41, true));
    }

    @Test void nonClickInputRequiresFreshUnchangedCaptureInsteadOfCachedControlIdentity() {
        DesktopAction action = new DesktopAction(DesktopAction.Kind.TYPE, 1, 1, 0, 0, 0, "text", 1, "observation", "e1", 1);
        DesktopFrame observed = frame("target", 1, 1, 500);
        assertTrue(WindowsSession.sameObservation(observed, frame("target", 1, 1, 600), action, 1000));
        assertFalse(WindowsSession.sameObservation(observed, null, action, 1000));
        assertFalse(WindowsSession.sameObservation(observed, frame("target", 1, 2, 600), action, 1000));
        assertFalse(WindowsSession.sameObservation(observed, frame("target", 2, 1, 600), action, 1000));
        assertFalse(WindowsSession.sameObservation(observed, frame("other", 1, 1, 600), action, 1000));
        assertFalse(WindowsSession.sameObservation(observed, frame("target", 1, 1, 499), action, 1000));
        assertFalse(WindowsSession.sameObservation(observed, frame("target", 1, 1, 1001), action, 1000));
        assertFalse(WindowsSession.sameObservation(observed, frame("target", 1, 1, 600), action, 3101));
    }

    @Test void foregroundTextFocusClickRetainsTheAuthorizedObservationAndUsesOneLeftPress() {
        DesktopAction type = new DesktopAction(DesktopAction.Kind.TYPE, 7, 9, 0, 0, 0, "text", 3, "obs3", "e4", 12);
        DesktopAction focus = WindowsInput.focusClick(type);
        assertEquals(DesktopAction.Kind.CLICK, focus.kind());
        assertEquals(1, focus.button());
        assertEquals(1, focus.clicks());
        assertEquals(7, focus.x());
        assertEquals(9, focus.y());
        assertEquals(3, focus.windowGeneration());
        assertEquals("obs3", focus.observationId());
        assertEquals("e4", focus.elementId());
        assertEquals(12, focus.contentRevision());
    }

    @Test void discoveryOmitsToolWindowsUntitledHiddenAndOtherVirtualDesktopWindows() {
        assertTrue(WindowsWindows.eligibleWindow(true, 0x40000, "Application", false));
        assertFalse(WindowsWindows.eligibleWindow(true, 0x40080, "Floating tool", false));
        assertFalse(WindowsWindows.eligibleWindow(true, 0, "", false));
        assertFalse(WindowsWindows.eligibleWindow(false, 0, "Hidden", false));
        assertFalse(WindowsWindows.eligibleWindow(true, 0, "Other desktop", true));
    }

    @Test void versionAdmissionRetainsTheWindows11BuildContract() {
        assertFalse(WindowsDesktopApi.supportedVersion(6, 7601));
        assertFalse(WindowsDesktopApi.supportedVersion(10, 19045));
        assertTrue(WindowsDesktopApi.supportedVersion(10, 22000));
        assertTrue(WindowsDesktopApi.supportedVersion(10, 26100));
    }

    private static DesktopFrame frame(String target, long generation, long revision, long capturedAt) {
        return new DesktopFrame(target, generation, capturedAt, 2, 2, 8, new byte[16], revision,
                new DesktopFrameGeometry(2, 2, 0, 0, 2, 2, false));
    }
}
