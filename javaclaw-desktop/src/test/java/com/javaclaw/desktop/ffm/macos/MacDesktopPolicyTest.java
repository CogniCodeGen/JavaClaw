package com.javaclaw.desktop.ffm.macos;

import com.javaclaw.desktop.api.DesktopAction;
import com.javaclaw.desktop.api.DesktopFrame;
import com.javaclaw.desktop.api.DesktopFrameGeometry;
import com.javaclaw.desktop.nativebridge.DesktopBridge.NativeWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MacDesktopPolicyTest {
    @Test void accessibilityTargetsExcludeSelfAndInvalidPidsWhileKeepingOtherApplications() {
        assertFalse(MacTargetPolicy.externalProcess(42, 42));
        assertTrue(MacTargetPolicy.externalProcess(43, 42));
        assertFalse(MacTargetPolicy.externalProcess(0, 42));
        assertFalse(MacTargetPolicy.externalProcess(-1, 42));
        assertFalse(MacTargetPolicy.externalProcess((long) Integer.MAX_VALUE + 1, 42));
        long self = ProcessHandle.current().pid();
        assertFalse(MacTargetPolicy.externalProcess(self));
        assertTrue(MacTargetPolicy.externalProcess(self == 1 ? 2 : self - 1));
        assertThrows(SecurityException.class, () -> MacTargetPolicy.requireExternalProcess(self));
    }

    @Test void selfProcessObservationInputFocusAndBindingStopBeforeAnyNativeCall() {
        long self = ProcessHandle.current().pid();
        var target = new NativeWindow(self, 20, 30, 100, 200, 100, 50, 2, "JavaClaw", "window", "id");
        // A missing API makes any accidental native access fail this pure Java test.
        var accessibility = new MacAccessibility(null);
        assertEquals(java.lang.foreign.MemorySegment.NULL, accessibility.application(self));
        assertEquals(java.lang.foreign.MemorySegment.NULL, accessibility.window(target));
        assertFalse(accessibility.focused(target));
        assertFalse(accessibility.belongs(java.lang.foreign.MemorySegment.NULL, target));
        assertEquals(List.of(target), accessibility.decorate(List.of(target)));
        assertEquals(target, accessibility.decorateOne(target, List.of(target)));
        assertTrue(accessibility.observe(target, null).isEmpty());
        var action = new DesktopAction(DesktopAction.Kind.CLICK, 1, 1, 1, 1, 0, "", 1);
        assertEquals(com.javaclaw.desktop.api.DesktopActionResult.Status.DENIED,
                accessibility.perform(target, action, null, false, () -> {
                    fail("Self-process input must never reach dispatch admission"); return true;
                }).status());
        var windows = new MacWindows(null);
        assertThrows(SecurityException.class, () -> windows.requireOriginal(target));
        assertThrows(SecurityException.class, () -> windows.refresh(target));
        assertThrows(SecurityException.class, () -> windows.withWindow(target, ignored -> null));
    }

    @Test void nativeThreadLocalScopesRejectVirtualThreadsBeforeCallingMacOS() throws Exception {
        assertDoesNotThrow(MacNative::requirePlatformThread);
        var observed = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread virtual = Thread.ofVirtual().start(() -> {
            try { MacNative.requirePlatformThread(); }
            catch (Throwable failure) { observed.set(failure); }
        });
        virtual.join();
        assertInstanceOf(IllegalStateException.class, observed.get());
    }

    @Test void pointerHitTestingSkipsNoncoveringWindowsButNeverSkipsARealOverlay() {
        assertEquals(1, MacInput.firstHitIndex(List.of(new double[]{0, 0, 10, 10},
                new double[]{50, 50, 100, 100}), new double[]{75, 75}));
        assertEquals(0, MacInput.firstHitIndex(List.of(new double[]{60, 60, 30, 30},
                new double[]{50, 50, 100, 100}), new double[]{75, 75}));
        assertEquals(-1, MacInput.firstHitIndex(List.of(new double[]{0, 0, 10, 10}), new double[]{75, 75}));
        assertEquals(-1, MacInput.firstHitIndex(List.of(new double[]{Double.NaN, 0, 100, 100}), new double[]{75, 75}));
    }

    @Test void mouseButtonsKeepThePublicMiddleAndRightMeanings() {
        assertEquals(0, MacInput.cgButton(1));
        assertEquals(2, MacInput.cgButton(2));
        assertEquals(1, MacInput.cgButton(3));
        assertThrows(IllegalArgumentException.class, () -> MacInput.cgButton(4));
    }

    @Test void keyParsingAcceptsKnownModifiersAndFailsClosedForUnknownParts() {
        var key = MacInput.parseKey(" control + shift + a ");
        assertNotNull(key);
        assertEquals(0, key.code());
        assertEquals((1L << 18) | (1L << 17), key.flags());
        assertNull(MacInput.parseKey("SUPER+A"));
        assertNull(MacInput.parseKey("CMD+"));
        assertNull(MacInput.parseKey("UNKNOWN"));
    }

    @Test void unicodeChunkingNeverSplitsAnEmojiSurrogatePair() {
        String text = "a".repeat(63) + "😀" + "b".repeat(64);
        var chunks = MacInput.unicodeChunks(text, 64);
        assertEquals(text, String.join("", chunks));
        assertEquals(63, chunks.getFirst().length());
        assertTrue(chunks.stream().allMatch(value -> value.length() <= 64));
        assertTrue(chunks.stream().noneMatch(value -> Character.isLowSurrogate(value.charAt(0))));
    }

    @Test void processBirthIdentityIsBoundedAndDoesNotAcceptInvalidMicroseconds() {
        assertEquals(1_234_567_890_123_456L, MacWindows.birthIdentity(1_234_567_890L, 123_456));
        assertEquals(0, MacWindows.birthIdentity(0, 0));
        assertEquals(0, MacWindows.birthIdentity(1, -1));
        assertEquals(0, MacWindows.birthIdentity(1, 1_000_000));
        assertEquals(0, MacWindows.birthIdentity(Long.MAX_VALUE, 0));
    }

    @Test void statusItemClassificationPreservesRealControlCenterPanels() {
        assertTrue(MacWindows.foreignStatusItem("com.apple.controlcenter", "com.tencent.qq"));
        assertFalse(MacWindows.foreignStatusItem("com.apple.controlcenter", "com.apple.controlcenter"));
        assertFalse(MacWindows.foreignStatusItem("com.apple.controlcenter", "Control Center"));
        assertFalse(MacWindows.foreignStatusItem("com.apple.controlcenter", "com..qq"));
        assertFalse(MacWindows.foreignStatusItem("other.app", "com.tencent.qq"));
    }

    @Test void screenshotSizingBoundsRetinaFramesWithoutChangingAspectRatio() {
        assertArrayEquals(new int[]{2000, 1000}, MacCapture.captureSize(1000, 500, 2));
        assertArrayEquals(new int[]{4096, 2048}, MacCapture.captureSize(4000, 2000, 2));
        assertThrows(IllegalArgumentException.class, () -> MacCapture.captureSize(Double.NaN, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> MacCapture.captureSize(10, -1, 2));
    }

    @Test void accessibilityBoundsAreClippedToTheSelectedWindowAndRejectNonfiniteValues() {
        var window = new NativeWindow(10, 20, 30, 100, 200, 100, 50, 2, "app", "window", "id");
        DesktopFrame frame = frame(new byte[200 * 100 * 4]);
        assertArrayEquals(new int[]{0, 0, 40, 20},
                MacAccessibility.frameBounds(new double[]{90, 190, 30, 20}, window, frame));
        assertNull(MacAccessibility.frameBounds(new double[]{0, 0, 30, 20}, window, frame));
        assertNull(MacAccessibility.frameBounds(new double[]{Double.NaN, 200, 30, 20}, window, frame));
        assertFalse(MacAccessibility.sameBounds(new double[]{Double.NaN, 0, 1, 1}, new double[]{0, 0, 1, 1}));
    }

    @Test void semanticSnapshotIgnoresPixelsOutsideItsRegionAndRejectsChangedControlPixels() {
        byte[] pixels = new byte[200 * 100 * 4];
        DesktopFrame before = frame(pixels);
        pixels[4] = 1; // outside the observed control at 20,20
        DesktopFrame outside = frame(pixels);
        assertTrue(MacAccessibility.snapshotPixelsMatch(before, outside, new int[]{20, 20, 10, 10},
                before.copyBgraRegion(20, 20, 10, 10)));
        pixels[(20 * 200 + 20) * 4] = 1;
        assertFalse(MacAccessibility.snapshotPixelsMatch(before, frame(pixels), new int[]{20, 20, 10, 10},
                before.copyBgraRegion(20, 20, 10, 10)));
    }

    @Test void anUncachedLargeControlCanProceedOnlyWhenTheWholeFrameIsUnchanged() {
        DesktopFrame observed = frame(new byte[200 * 100 * 4]);
        assertTrue(MacAccessibility.snapshotPixelsMatch(observed, frame(new byte[200 * 100 * 4]),
                new int[]{0, 0, 200, 100}, null));
        byte[] changed = new byte[200 * 100 * 4];
        changed[changed.length - 1] = 1;
        assertFalse(MacAccessibility.snapshotPixelsMatch(observed, frame(changed),
                new int[]{0, 0, 200, 100}, null));
    }

    @Test void secureSubroleAndExplicitTextOperationsRetainTheirMeaning() {
        assertTrue(MacAccessibility.protectedRole("AXTextField", "AXSecureTextField"));
        assertTrue(MacAccessibility.protectedRole("AXSecureTextField", ""));
        assertFalse(MacAccessibility.protectedRole("AXTextField", "AXSearchField"));
        assertEquals("AXSelectedText", MacAccessibility.textAttribute(DesktopAction.TextOperation.INSERT_TEXT));
        assertEquals("AXValue", MacAccessibility.textAttribute(DesktopAction.TextOperation.SET_TEXT));
    }

    @Test void backgroundScrollUsesOnlyAnAdvertisedActionInTheRequestedDirection() {
        assertEquals("AXIncrement", MacAccessibility.scrollAction(List.of("AXIncrement", "AXDecrement"), 1));
        assertEquals("AXDecrement", MacAccessibility.scrollAction(List.of("AXIncrement", "AXDecrement"), -1));
        assertEquals("AXScrollDownByPage", MacAccessibility.scrollAction(List.of("AXScrollDownByPage"), 1));
        assertEquals("", MacAccessibility.scrollAction(List.of("AXScrollDownByPage"), -1));
        assertEquals("", MacAccessibility.scrollAction(List.of("AXPress"), 1));
    }

    @Test void foregroundTypeFocusesTheObservedPointThenWaitsBeforeUnicodeInput() {
        assertTrue(MacInput.pointerRequired(DesktopAction.Kind.TYPE, 0));
        assertTrue(MacInput.pointerRequired(DesktopAction.Kind.TYPE, 1));
        assertFalse(MacInput.pointerRequired(DesktopAction.Kind.TYPE, 2));
        var posted = new ArrayList<String>();
        var delays = new ArrayList<Long>();
        var outcome = MacEventSequence.deliver(List.of("focus-down", "focus-up", "text-down", "text-up"),
                true, () -> true, ignored -> true, posted::add,
                index -> delays.add(MacInput.eventDelayMillis(DesktopAction.Kind.TYPE, index)));
        assertEquals(MacEventSequence.Outcome.ACCEPTED, outcome);
        assertEquals(List.of("focus-down", "focus-up", "text-down", "text-up"), posted);
        assertEquals(List.of(10L, 50L, 10L, 10L), delays);
    }

    @Test void foregroundTypeLosingFocusAfterTheFocusClickIsUnknownAndSendsNoText() {
        var posted = new ArrayList<String>();
        AtomicInteger readiness = new AtomicInteger();
        var outcome = MacEventSequence.deliver(List.of("focus-down", "focus-up", "text-down", "text-up"),
                true, () -> readiness.incrementAndGet() <= 2, ignored -> true, posted::add, ignored -> {});
        assertEquals(MacEventSequence.Outcome.UNKNOWN, outcome);
        assertEquals(List.of("focus-down", "focus-up"), posted);
    }

    @Test void foregroundTypeWaitFailureAfterClickIsUnknownAndDoesNotRepeatTheFocusClick() {
        var posted = new ArrayList<String>();
        var outcome = MacEventSequence.deliver(List.of("focus-down", "focus-up", "text-down", "text-up"),
                true, () -> true, ignored -> true, posted::add, index -> {
                    if (index == 1) throw new IllegalStateException("focus wait interrupted");
                });
        assertEquals(MacEventSequence.Outcome.UNKNOWN, outcome);
        assertEquals(List.of("focus-down", "focus-up"), posted);
    }

    @Test void changedPixelsBeforeSecondClickStopWithUnknownWithoutPostingAnotherDown() {
        var posts = new ArrayList<String>();
        var result = MacEventSequence.deliver(List.of("down1", "up1", "down2", "up2"), true,
                () -> true, index -> index != 2, posts::add, () -> {});
        assertEquals(MacEventSequence.Outcome.UNKNOWN, result);
        assertEquals(List.of("down1", "up1"), posts);
    }

    @Test void partialDoubleClickReleasesHeldInputWithoutReplayingTheClick() {
        var posts = new ArrayList<String>();
        AtomicInteger checks = new AtomicInteger();
        var result = MacEventSequence.deliver(List.of("down1", "up1", "down2", "up2"), true,
                () -> checks.incrementAndGet() == 1, posts::add, () -> {});
        assertEquals(MacEventSequence.Outcome.UNKNOWN, result);
        assertEquals(List.of("down1", "up1"), posts);
    }

    @Test void exceptionInsideFirstSystemPostIsUnknownAndOnlyItsReleaseIsRetried() {
        var posts = new ArrayList<String>();
        var result = MacEventSequence.deliver(List.of("down", "up"), true, () -> true, event -> {
            posts.add(event);
            if (event.equals("down")) throw new IllegalStateException("delivery cannot be confirmed");
        }, () -> {});
        assertEquals(MacEventSequence.Outcome.UNKNOWN, result);
        assertEquals(List.of("down", "up"), posts);
    }

    @Test void rejectedPreflightSendsNoInputAndCompletedSequenceIsAccepted() {
        var posts = new ArrayList<String>();
        assertEquals(MacEventSequence.Outcome.NOT_SENT, MacEventSequence.deliver(List.of("down", "up"),
                true, () -> false, posts::add, () -> {}));
        assertTrue(posts.isEmpty());
        assertEquals(MacEventSequence.Outcome.ACCEPTED, MacEventSequence.deliver(List.of("down", "up"),
                true, () -> true, posts::add, () -> {}));
        assertEquals(List.of("down", "up"), posts);
    }

    private DesktopFrame frame(byte[] bytes) {
        return new DesktopFrame("target", 1, 100, 200, 100, 800, bytes, 1,
                new DesktopFrameGeometry(100, 50, 0, 0, 200, 100, true));
    }
}
