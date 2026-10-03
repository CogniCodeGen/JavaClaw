package com.javaclaw.desktop.nativebridge;

import com.javaclaw.desktop.api.DesktopAction;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopBridgeElementDispatchTest {
    private static final String OBSERVATION = "11111111-1111-1111-1111-111111111111";

    @Test
    void unsignedNativeIndexSurvivesElementIdRoundTrip() {
        assertEquals("e4294967295", DesktopBridge.elementId(-1));
        assertEquals(-1, DesktopBridge.elementIndexFor(
                click(OBSERVATION + ":e4294967295", 1, 1), false).orElseThrow());
        assertEquals(42, DesktopBridge.elementIndexFor(click("e42", 1, 1), false)
                .orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> DesktopBridge.elementIndexFor(click(OBSERVATION + ":e4294967296", 1, 1), false));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopBridge.elementIndexFor(click(OBSERVATION + ":e0", 1, 1), false));
        assertThrows(IllegalArgumentException.class,
                () -> DesktopBridge.elementIndexFor(click("other-observation:e1", 1, 1), false));
    }

    @Test
    void zeroIndexRemainsVisibleOnLegacyPlatformButNotTokenizedAx() {
        assertEquals("e0", DesktopBridge.elementId(0));
        assertTrue(DesktopBridge.acceptsElementIndex(0, false),
                "Windows and ABI-compatible libraries without the optional symbol use zero-based positions");
        assertFalse(DesktopBridge.acceptsElementIndex(0, true),
                "the optional native token path reserves zero as invalid");
        assertTrue(DesktopBridge.acceptsElementIndex(-1, true),
                "a high unsigned token must not be mistaken for an invalid signed index");
    }

    @Test
    void onlyBackgroundSingleLeftAxClickUsesElementDispatch() throws Throwable {
        Probe probe = new Probe();
        MethodHandle element = handle(probe);
        AtomicInteger legacyCalls = new AtomicInteger();
        DesktopBridge.LegacyPerformer legacy = (session, input, detail, capacity) -> {
            legacyCalls.incrementAndGet();
            return 7;
        };

        assertEquals(1, dispatch(click(OBSERVATION + ":e27", 1, 1), false,
                element, legacy));
        assertEquals(27, probe.lastIndex);
        assertEquals(1, probe.calls);
        assertEquals(0, legacyCalls.get());

        assertEquals(7, dispatch(click(OBSERVATION + ":v0", 1, 1), false,
                element, legacy));
        assertEquals(7, dispatch(click(OBSERVATION + ":e27", 1, 2), false,
                element, legacy));
        assertEquals(7, dispatch(click(OBSERVATION + ":e27", 3, 1), false,
                element, legacy));
        assertEquals(7, dispatch(click(OBSERVATION + ":e27", 1, 1), true,
                element, legacy));
        assertEquals(7, dispatch(new DesktopAction(DesktopAction.Kind.TYPE,
                10, 10, 0, 0, 0, "text", 1, OBSERVATION,
                OBSERVATION + ":e27", 1), false, element, legacy));
        assertEquals(1, probe.calls);
        assertEquals(5, legacyCalls.get());

        assertEquals(7, dispatch(click(OBSERVATION + ":e27", 1, 1), false,
                null, legacy));
        assertEquals(6, legacyCalls.get(), "an ABI-compatible older library uses the existing path");
    }

    @Test
    void elementResultOrExceptionNeverReplaysAsCoordinateClick() throws Throwable {
        Probe probe = new Probe();
        AtomicInteger legacyCalls = new AtomicInteger();
        DesktopBridge.LegacyPerformer legacy = (session, input, detail, capacity) -> {
            legacyCalls.incrementAndGet();
            return 0;
        };
        DesktopAction click = click(OBSERVATION + ":e1", 1, 1);

        assertEquals(1, dispatch(click, false, handle(probe), legacy),
                "UNKNOWN may mean the native action was already dispatched");
        probe.throwAfterAttempt = true;
        assertThrows(IllegalStateException.class,
                () -> dispatch(click, false, handle(probe), legacy));
        assertEquals(2, probe.calls);
        assertEquals(0, legacyCalls.get());
    }

    @Test
    void optionalDiagnosticFailureIsBestEffortAndDoesNotExposeNativeText() throws Exception {
        assertEquals("", DesktopBridge.readElementDiagnostics(null, MemorySegment.NULL));
        DiagnosticsProbe probe = new DiagnosticsProbe();
        MethodHandle diagnostics = MethodHandles.lookup().findVirtual(DiagnosticsProbe.class,
                "read", MethodType.methodType(int.class, MemorySegment.class,
                        MemorySegment.class, int.class)).bindTo(probe);

        probe.status = -7;
        assertEquals("辅助功能元素诊断不可用（状态 -7）",
                DesktopBridge.readElementDiagnostics(diagnostics, MemorySegment.NULL));
        probe.throwAfterWriting = true;
        assertEquals("辅助功能元素诊断不可用（调用失败）",
                DesktopBridge.readElementDiagnostics(diagnostics, MemorySegment.NULL));
    }

    private static DesktopAction click(String elementId, int button, int clicks) {
        return new DesktopAction(DesktopAction.Kind.CLICK, 10, 10, button, clicks,
                0, "", 1, OBSERVATION, elementId, 1);
    }

    private static int dispatch(DesktopAction action, boolean foreground,
            MethodHandle element, DesktopBridge.LegacyPerformer legacy) throws Throwable {
        return DesktopBridge.dispatchAction(action, foreground, element,
                MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, 512, legacy);
    }

    private static MethodHandle handle(Probe probe) throws ReflectiveOperationException {
        return MethodHandles.lookup().findVirtual(Probe.class, "perform",
                MethodType.methodType(int.class, MemorySegment.class,
                        MemorySegment.class, int.class, MemorySegment.class, int.class))
                .bindTo(probe);
    }

    private static final class Probe {
        private int calls;
        private int lastIndex;
        private boolean throwAfterAttempt;

        @SuppressWarnings("unused")
        private int perform(MemorySegment session, MemorySegment input, int index,
                MemorySegment detail, int capacity) {
            calls++;
            lastIndex = index;
            if (throwAfterAttempt) throw new IllegalStateException("native result lost after dispatch");
            return 1;
        }
    }

    private static final class DiagnosticsProbe {
        private int status;
        private boolean throwAfterWriting;

        @SuppressWarnings("unused")
        private int read(MemorySegment session, MemorySegment detail, int capacity) {
            byte[] privateText = "private field value".getBytes(StandardCharsets.UTF_8);
            detail.asSlice(0, privateText.length).copyFrom(MemorySegment.ofArray(privateText));
            if (throwAfterWriting) throw new IllegalStateException("private field value");
            return status;
        }
    }
}
