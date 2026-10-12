package com.javaclaw.desktop.ffm.macos;

import com.javaclaw.desktop.api.DesktopInputPolicy;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in system reads only: no capture, focus, launch, input or permission request. */
@EnabledOnOs(OS.MAC)
@EnabledIfSystemProperty(named = "javaclaw.native.public.tests", matches = "true")
class MacSystemFfmSmokeTest {
    @Test void ownProcessAccessibilityAttributesAreRejectedBeforeAppKitCanReenterGlass() {
        var api = new MacNative();
        try (var pool = api.pool()) {
            MemorySegment self = (MemorySegment) api.call("AXUIElementCreateApplication", ValueLayout.ADDRESS,
                    new MemoryLayout[]{ValueLayout.JAVA_INT}, (int) ProcessHandle.current().pid());
            try {
                assertFalse(MacNative.nil(self));
                assertEquals(ProcessHandle.current().pid(), api.accessibilityOwner(self));
                assertTrue(MacNative.nil(api.attribute(self, "AXWindows")));
                assertTrue(MacNative.nil(api.attribute(self, "AXFocusedWindow")));
                assertTrue(MacNative.nil(api.attribute(MemorySegment.NULL, "AXWindows")));
            } finally { api.release(self); }
        }
    }

    @Test void foundationLibprocAndPermissionNeutralMetadataCallsUseOnlySystemLibraries() {
        var api = new MacNative();
        try (var pool = api.pool()) {
            assertEquals("Java FFM ✓\0尾部", api.text(api.string("Java FFM ✓\0尾部")));
            assertTrue(new MacWindows(api).instance(ProcessHandle.current().pid()) > 0);
            var desktop = new MacDesktopApi();
            assertNotNull(desktop.availability(false, DesktopInputPolicy.BACKGROUND_STRICT));
            assertNotNull(desktop.windows());
            assertTrue(desktop.applications().applications().size() <= 256);
        }
    }

    @Test void objectiveCCompletionBlockCrossesTheRealFfmBoundaryAndRetainsItsResult() throws Throwable {
        var api = new MacNative();
        try (var pool = api.pool(); var completion = new MacAsync(api)) {
            invoke(completion, api.string("completed"));
            assertEquals("completed", api.text(completion.await(Duration.ofSeconds(1)).pointer()));
        }
    }

    @Test void timedOutBlockRemainsAliveUntilItsLateCompletionAndThenReleasesSafely() throws Throwable {
        var api = new MacNative();
        try (var pool = api.pool()) {
            var completion = new MacAsync(api);
            MemorySegment nativeCopy = (MemorySegment) api.call("_Block_copy", ValueLayout.ADDRESS,
                    new MemoryLayout[]{ValueLayout.ADDRESS}, completion.pointer());
            assertThrows(IllegalStateException.class, () -> completion.await(Duration.ofMillis(1)));
            completion.close();
            completion.close(); // Only our native reference may be released, once.
            assertFalse(completion.disposed());
            invoke(completion, api.string("late completion"));
            assertFalse(completion.disposed()); // Native may retain the block after callback.
            api.call("_Block_release", null, new MemoryLayout[]{ValueLayout.ADDRESS}, nativeCopy);
            assertTrue(completion.disposed());
        }
    }

    @Test void repeatedNativeBlockLifetimesUseFixedStubsAndReleaseAllJavaOwners() throws Throwable {
        var api = new MacNative();
        int baseline = MacBlock.activeCount();
        var invokeAddresses = new HashSet<Long>();
        var descriptorAddresses = new HashSet<Long>();
        try (var pool = api.pool()) {
            for (int i = 0; i < 256; i++) {
                var completion = new MacAsync(api);
                MemorySegment pointer = completion.pointer();
                invokeAddresses.add(pointer.get(ValueLayout.ADDRESS, 16).address());
                descriptorAddresses.add(pointer.get(ValueLayout.ADDRESS, 24).address());
                assertEquals(baseline + 1, MacBlock.activeCount());
                invoke(completion, api.string("iteration " + i));
                assertEquals("iteration " + i, api.text(completion.await(Duration.ofSeconds(1)).pointer()));
                completion.close();
                completion.close();
                assertTrue(completion.disposed());
                assertEquals(baseline, MacBlock.activeCount());
            }
        }
        assertEquals(1, invokeAddresses.size());
        assertEquals(1, descriptorAddresses.size());
        assertEquals(4, MacBlock.stubCount());
    }

    @Test void autoreleasePoolCannotBeCreatedOnVirtualThreadsOrDrainedByAnotherThread() throws Exception {
        var api = new MacNative();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread virtual = Thread.ofVirtual().start(() -> {
            try { assertThrows(IllegalStateException.class, api::pool); }
            catch (Throwable assertion) { failure.set(assertion); }
        });
        virtual.join();
        var pool = api.pool();
        Thread foreign = Thread.ofPlatform().start(() -> {
            try { assertThrows(IllegalStateException.class, pool::close); }
            catch (Throwable assertion) { failure.set(assertion); }
        });
        foreign.join();
        pool.close();
        pool.close();
        assertNull(failure.get());
    }

    @Test void outstandingNativeCallbacksStayBoundedUntilNativeFinalDispose() {
        var api = new MacNative();
        int baseline = MacBlock.activeCount();
        var callbacks = new java.util.ArrayList<MacAsync>();
        try {
            for (int i = baseline; i < 64; i++) callbacks.add(new MacAsync(api));
            assertEquals(64, MacBlock.activeCount());
            assertThrows(IllegalStateException.class, () -> new MacAsync(api));
        } finally { callbacks.forEach(MacAsync::close); }
        assertEquals(baseline, MacBlock.activeCount());
    }

    @Test void syntheticImageDecodingPreservesBgraColorsAndRowOrientation() {
        var api = new MacNative();
        try (var arena = Arena.ofConfined()) {
            byte[] expected = {0, 0, (byte) 255, (byte) 255, 0, (byte) 255, 0, (byte) 255,
                    (byte) 255, 0, 0, (byte) 255, (byte) 255, (byte) 255, 0, (byte) 255};
            MemorySegment pixels = arena.allocateFrom(ValueLayout.JAVA_BYTE, expected);
            MemorySegment color = (MemorySegment) api.call("CGColorSpaceCreateDeviceRGB", ValueLayout.ADDRESS,
                    new MemoryLayout[0]);
            MemorySegment context = MemorySegment.NULL;
            MemorySegment image = MemorySegment.NULL;
            try {
                context = (MemorySegment) api.call("CGBitmapContextCreate", ValueLayout.ADDRESS,
                        new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT},
                        pixels, 2L, 2L, 8L, 8L, color, 0x2002);
                assertNotEquals(0, context.address());
                image = (MemorySegment) api.call("CGBitmapContextCreateImage", ValueLayout.ADDRESS,
                        new MemoryLayout[]{ValueLayout.ADDRESS}, context);
                assertNotEquals(0, image.address());
                var decoded = new MacCapture(api, new MacWindows(api)).decode(image, 2, 2);
                assertEquals(2, decoded.width());
                assertEquals(2, decoded.height());
                assertArrayEquals(expected, decoded.bytes());
            } finally { api.release(image); api.release(context); api.release(color); }
        }
    }

    private static void invoke(MacAsync completion, MemorySegment value) throws Throwable {
        var pointer = completion.pointer();
        var callback = Linker.nativeLinker().downcallHandle(pointer.get(ValueLayout.ADDRESS, 16),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        callback.invoke(pointer, value, MemorySegment.NULL);
    }
}
