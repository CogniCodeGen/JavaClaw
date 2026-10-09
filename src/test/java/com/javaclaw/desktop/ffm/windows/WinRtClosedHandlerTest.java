package com.javaclaw.desktop.ffm.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.javaclaw.desktop.ffm.windows.Win32.*;

class WinRtClosedHandlerTest {
    @Test void delegatesShareOnlyFourStaticStubsAndFinalReleaseRemovesTheirJavaOwners() {
        int baseline = WinRtClosedHandler.activeCount();
        var handlers = new ArrayList<WinRtClosedHandler>();
        var objects = new HashSet<Long>();
        long tableAddress = 0;
        boolean eventReference = false;
        try {
            for (int i = 0; i < 32; i++) {
                var handler = new WinRtClosedHandler(new AtomicBoolean());
                handlers.add(handler);
                objects.add(handler.address().address());
                MemorySegment table = handler.address().get(P, 0);
                if (i == 0) tableAddress = table.address();
                assertEquals(tableAddress, table.address());
                assertEquals(4, WinRtClosedHandler.stubCount());
            }
            assertEquals(32, objects.size());
            assertEquals(baseline + 32, WinRtClosedHandler.activeCount());
            WinRtClosedHandler retained = handlers.getFirst();
            assertEquals(2, call(retained.address(), 1, new MemoryLayout[0]));
            eventReference = true;
            for (WinRtClosedHandler handler : handlers) handler.releaseOwner();
            assertEquals(baseline + 1, WinRtClosedHandler.activeCount(), "native reference must retain its Java owner");
            assertEquals(0, call(retained.address(), 2, new MemoryLayout[0]));
            eventReference = false;
            assertEquals(baseline, WinRtClosedHandler.activeCount(), "final COM release removes all operation state from LIVE");
        } finally {
            for (WinRtClosedHandler handler : handlers) handler.releaseOwner();
            if (eventReference) call(handlers.getFirst().address(), 2, new MemoryLayout[0]);
        }
    }

    @Test void exposesOnlyItsThreeComInterfacesAndClearsRejectedOutputs() {
        WinRtClosedHandler handler = new WinRtClosedHandler(new AtomicBoolean());
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(P);
            for (String iid : new String[]{"00000000-0000-0000-c000-000000000046",
                    "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90", WinRtClosedHandler.HANDLER_IID}) {
                assertEquals(0, call(handler.address(), 0, new MemoryLayout[]{P, P}, guid(arena, iid), out));
                assertEquals(handler.address().address(), out.get(P, 0).address());
                assertEquals(1, call(out.get(P, 0), 2, new MemoryLayout[0]));
            }
            out.set(P, 0, handler.address());
            assertEquals(0x80004002, call(handler.address(), 0, new MemoryLayout[]{P, P},
                    guid(arena, "af86e2e0-b12d-4c6a-9c5a-d7aa65101e90"), out));
            assertEquals(0, out.get(P, 0).address(), "a delegate does not expose IInspectable");
            assertEquals(0x80004003, call(handler.address(), 0, new MemoryLayout[]{P, P},
                    MemorySegment.NULL, out));
            assertEquals(0, out.get(P, 0).address());
        } finally { handler.releaseOwner(); }
    }

    @Test void eventReferenceSurvivesOwnerReleaseUntilNativeRelease() {
        AtomicBoolean closed = new AtomicBoolean();
        WinRtClosedHandler handler = new WinRtClosedHandler(closed);
        MemorySegment address = handler.address();
        assertEquals(2, call(address, 1, new MemoryLayout[0]));
        handler.releaseOwner();
        handler.releaseOwner();
        assertFalse(closed.get());
        assertEquals(0, call(address, 3, new MemoryLayout[]{P, P}, MemorySegment.NULL, MemorySegment.NULL));
        assertTrue(closed.get());
        assertEquals(0, call(address, 2, new MemoryLayout[0]));
        assertEquals(0, call(address, 1, new MemoryLayout[0]), "released delegates cannot be resurrected");
        closed.set(false);
        assertEquals(0x80004005, call(address, 3, new MemoryLayout[]{P, P}, MemorySegment.NULL, MemorySegment.NULL));
        assertFalse(closed.get());
        // The Java reference deliberately keeps its auto arena alive for these invalid-lifetime checks.
        java.lang.ref.Reference.reachabilityFence(handler);
    }

    @Test void invokeIsCallableFromAnotherNativeCarrierThread() throws InterruptedException {
        AtomicBoolean closed = new AtomicBoolean();
        WinRtClosedHandler handler = new WinRtClosedHandler(closed);
        try {
            AtomicInteger result = new AtomicInteger(-1);
            Thread thread = Thread.ofPlatform().start(() -> result.set(call(handler.address(), 3,
                    new MemoryLayout[]{P, P}, MemorySegment.NULL, MemorySegment.NULL)));
            thread.join();
            assertEquals(0, result.get());
            assertTrue(closed.get());
        } finally { handler.releaseOwner(); }
    }

    private static int call(MemorySegment object, int slot, MemoryLayout[] parameters, Object... arguments) {
        MemoryLayout[] layouts = new MemoryLayout[parameters.length + 1];
        layouts[0] = P;
        System.arraycopy(parameters, 0, layouts, 1, parameters.length);
        Object[] values = new Object[arguments.length + 1];
        values[0] = object;
        System.arraycopy(arguments, 0, values, 1, arguments.length);
        MemorySegment table = object.reinterpret(P.byteSize()).get(P, 0);
        MemorySegment function = table.reinterpret((slot + 1L) * P.byteSize()).get(P, slot * P.byteSize());
        return (int) Win32.invoke(Linker.nativeLinker().downcallHandle(function,
                FunctionDescriptor.of(I, layouts)), values);
    }
}
