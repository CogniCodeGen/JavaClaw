package com.javaclaw.desktop.ffm.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.Reference;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static com.javaclaw.desktop.ffm.windows.Win32.*;

/** Agile COM delegate for GraphicsCaptureItem.Closed; callbacks only publish a Java flag. */
final class WinRtClosedHandler {
    // Windows SDK windows.graphics.capture.h: ITypedEventHandler<GraphicsCaptureItem*, IInspectable*>.
    static final String HANDLER_IID = "e9c610c0-a68c-5bd9-8021-8589346eeee2";
    private static final String UNKNOWN_IID = "00000000-0000-0000-c000-000000000046";
    private static final String AGILE_IID = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90";
    private static final int E_NOINTERFACE = 0x80004002;
    private static final int E_POINTER = 0x80004003;
    private static final int E_FAIL = 0x80004005;
    private static final ConcurrentHashMap<Long, WinRtClosedHandler> LIVE = new ConcurrentHashMap<>();

    // Only object memory is per delegate. Shared static stubs never capture this auto arena.
    // COM references retain this object through LIVE; after the last release, GC reclaims it.
    private final Arena arena = Arena.ofAuto();
    private final AtomicInteger references = new AtomicInteger(1);
    private final AtomicBoolean ownerReleased = new AtomicBoolean();
    private final AtomicBoolean closed;
    private final MemorySegment object;

    WinRtClosedHandler(AtomicBoolean closed) {
        this.closed = Objects.requireNonNull(closed);
        object = arena.allocate(P);
        object.set(P, 0, Stubs.TABLE);
        LIVE.put(object.address(), this);
    }

    MemorySegment address() { return object; }
    static int activeCount() { return LIVE.size(); }
    static int stubCount() { return Stubs.COUNT; }

    void releaseOwner() {
        if (ownerReleased.compareAndSet(false, true)) release(object);
    }

    private static WinRtClosedHandler owner(MemorySegment self) {
        return nullPointer(self) ? null : LIVE.get(self.address());
    }

    private static int queryInterface(MemorySegment self, MemorySegment iid, MemorySegment result) {
        WinRtClosedHandler owner = null;
        try {
            if (nullPointer(result)) return E_POINTER;
            MemorySegment output = result.reinterpret(P.byteSize());
            output.set(P, 0, MemorySegment.NULL);
            if (nullPointer(iid)) return E_POINTER;
            owner = owner(self);
            if (owner == null) return E_NOINTERFACE;
            MemorySegment requested = iid.reinterpret(16);
            if (requested.mismatch(Stubs.UNKNOWN) != -1 && requested.mismatch(Stubs.AGILE) != -1
                    && requested.mismatch(Stubs.HANDLER) != -1) return E_NOINTERFACE;
            if (owner.retain() == 0) return E_NOINTERFACE;
            output.set(P, 0, owner.object);
            return 0;
        } catch (Throwable failure) {
            return E_FAIL;
        } finally { Reference.reachabilityFence(owner); }
    }

    private static int addRef(MemorySegment self) {
        WinRtClosedHandler owner = null;
        try {
            owner = owner(self);
            return owner == null ? 0 : owner.retain();
        }
        catch (Throwable failure) { return 0; }
        finally { Reference.reachabilityFence(owner); }
    }

    private int retain() {
        for (;;) {
            int current = references.get();
            if (current <= 0 || current == Integer.MAX_VALUE) return 0;
            if (references.compareAndSet(current, current + 1)) return current + 1;
        }
    }

    private static int release(MemorySegment self) {
        WinRtClosedHandler owner = null;
        try {
            owner = owner(self);
            if (owner == null) return 0;
            for (;;) {
                int current = owner.references.get();
                if (current <= 0) return 0;
                int remaining = current - 1;
                if (owner.references.compareAndSet(current, remaining)) {
                    if (remaining == 0) LIVE.remove(owner.object.address(), owner);
                    return remaining;
                }
            }
        } catch (Throwable failure) {
            return 0;
        } finally { Reference.reachabilityFence(owner); }
    }

    private static int invoke(MemorySegment self, MemorySegment sender, MemorySegment args) {
        WinRtClosedHandler owner = null;
        try {
            owner = owner(self);
            if (owner == null || owner.references.get() <= 0) return E_FAIL;
            owner.closed.set(true);
            return 0;
        } catch (Throwable failure) {
            return E_FAIL;
        } finally { Reference.reachabilityFence(owner); }
    }

    /** Four process lifetime stubs/vtable entries; no global MethodHandle retains a delegate. */
    private static final class Stubs {
        private static final MemorySegment UNKNOWN = guid(Arena.global(), UNKNOWN_IID);
        private static final MemorySegment AGILE = guid(Arena.global(), AGILE_IID);
        private static final MemorySegment HANDLER = guid(Arena.global(), HANDLER_IID);
        private static final MemorySegment QUERY = stub("queryInterface", FunctionDescriptor.of(I, P, P, P));
        private static final MemorySegment RETAIN = stub("addRef", FunctionDescriptor.of(I, P));
        private static final MemorySegment RELEASE = stub("release", FunctionDescriptor.of(I, P));
        private static final MemorySegment INVOKE = stub("invoke", FunctionDescriptor.of(I, P, P, P));
        private static final int COUNT = java.util.Set.of(QUERY.address(), RETAIN.address(), RELEASE.address(), INVOKE.address()).size();
        private static final MemorySegment TABLE = table();

        private static MemorySegment stub(String name, FunctionDescriptor descriptor) {
            try {
                MethodHandle target = MethodHandles.lookup().findStatic(WinRtClosedHandler.class,
                        name, descriptor.toMethodType());
                return Linker.nativeLinker().upcallStub(target, descriptor, Arena.global());
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static MemorySegment table() {
            MemorySegment table = Arena.global().allocate(4L * P.byteSize(), P.byteAlignment());
            table.set(P, 0, QUERY);
            table.set(P, P.byteSize(), RETAIN);
            table.set(P, 2L * P.byteSize(), RELEASE);
            table.set(P, 3L * P.byteSize(), INVOKE);
            return table;
        }
    }
}
