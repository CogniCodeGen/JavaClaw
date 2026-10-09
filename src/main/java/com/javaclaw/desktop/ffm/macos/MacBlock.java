package com.javaclaw.desktop.ffm.macos;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Heap Objective-C blocks share permanent stubs; native final dispose releases Java state. */
final class MacBlock implements AutoCloseable {
    private static final long BLOCK_BYTES = 40;
    private static final long TOKEN_OFFSET = 32;
    private static final Semaphore OUTSTANDING = new Semaphore(64);
    private static final Map<Long, MacBlock> LIVE = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_TOKEN = new AtomicLong();
    private static final FunctionDescriptor ONE_ARGUMENT = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS);
    private static final FunctionDescriptor THREE_ARGUMENTS = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private final MacNative api;
    private final MethodHandle callback;
    private final long token;
    private final MemorySegment heap;
    private final AtomicBoolean released = new AtomicBoolean();
    private final AtomicBoolean disposed = new AtomicBoolean();

    MacBlock(MacNative api, MethodHandle callback, FunctionDescriptor signature) {
        MemorySegment invoke = signature.equals(ONE_ARGUMENT) ? Stubs.INVOKE_ONE
                : signature.equals(THREE_ARGUMENTS) ? Stubs.INVOKE_THREE : null;
        if (invoke == null || !callback.type().equals(signature.toMethodType()))
            throw new IllegalArgumentException("Unsupported macOS block callback signature");
        this.api = api;
        this.callback = callback;
        if (!OUTSTANDING.tryAcquire())
            throw new IllegalStateException("Too many outstanding macOS callbacks; no new operation was dispatched");
        token = NEXT_TOKEN.incrementAndGet();
        boolean copied = false;
        try (Arena stackArena = Arena.ofConfined()) {
            // _Block_copy copies the captured token along with the standard literal.
            // No per-operation upcall target can retain an arena through a JNI global handle.
            MemorySegment stack = stackArena.allocate(BLOCK_BYTES, 8);
            stack.set(ValueLayout.ADDRESS, 0, api.symbol("_NSConcreteStackBlock"));
            stack.set(ValueLayout.JAVA_INT, 8, 1 << 25); // BLOCK_HAS_COPY_DISPOSE
            stack.set(ValueLayout.ADDRESS, 16, invoke);
            stack.set(ValueLayout.ADDRESS, 24, Stubs.DESCRIPTOR);
            stack.set(ValueLayout.JAVA_LONG, TOKEN_OFFSET, token);
            LIVE.put(token, this);
            heap = (MemorySegment) api.call("_Block_copy", ValueLayout.ADDRESS,
                    new MemoryLayout[]{ValueLayout.ADDRESS}, stack);
            if (MacNative.nil(heap)) throw new IllegalStateException("macOS block allocation failed");
            copied = true;
        } finally {
            if (!copied && disposed.compareAndSet(false, true)) {
                LIVE.remove(token, this);
                OUTSTANDING.release();
            }
        }
    }

    MemorySegment pointer() { return heap.reinterpret(BLOCK_BYTES); }
    boolean disposed() { return disposed.get(); }
    static int activeCount() { return LIVE.size(); }
    static int stubCount() { return Stubs.COUNT; }

    private static MacBlock owner(MemorySegment block) {
        return MacNative.nil(block) ? null : LIVE.get(block.reinterpret(BLOCK_BYTES)
                .get(ValueLayout.JAVA_LONG, TOKEN_OFFSET));
    }

    private static void invokeOne(MemorySegment block) {
        try {
            MacBlock owner = owner(block);
            if (owner != null) owner.callback.invokeExact(block);
        } catch (Throwable ignored) { /* Never unwind through Objective-C. */ }
    }

    private static void invokeThree(MemorySegment block, MemorySegment value, MemorySegment error) {
        try {
            MacBlock owner = owner(block);
            if (owner != null) owner.callback.invokeExact(block, value, error);
        } catch (Throwable ignored) { /* Never unwind through Objective-C. */ }
    }

    private static void copy(MemorySegment destination, MemorySegment source) {
        // The runtime already copied the token. Heap copies retain the same native block.
    }

    private static void dispose(MemorySegment block) {
        try {
            MacBlock owner = owner(block);
            if (owner != null && owner.disposed.compareAndSet(false, true)
                    && LIVE.remove(owner.token, owner)) OUTSTANDING.release();
        } catch (Throwable ignored) { /* Never unwind through the native block runtime. */ }
    }

    @Override public void close() {
        if (released.compareAndSet(false, true))
            api.call("_Block_release", null, new MemoryLayout[]{ValueLayout.ADDRESS}, heap);
    }

    /** Fixed process lifetime resources cannot be reclaimed while native code returns through them. */
    private static final class Stubs {
        private static final MemorySegment INVOKE_ONE = stub("invokeOne", ONE_ARGUMENT);
        private static final MemorySegment INVOKE_THREE = stub("invokeThree", THREE_ARGUMENTS);
        private static final MemorySegment COPY = stub("copy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        private static final MemorySegment DISPOSE = stub("dispose", ONE_ARGUMENT);
        private static final int COUNT = java.util.Set.of(INVOKE_ONE.address(), INVOKE_THREE.address(),
                COPY.address(), DISPOSE.address()).size();
        private static final MemorySegment DESCRIPTOR = descriptor();

        private static MemorySegment stub(String name, FunctionDescriptor signature) {
            try {
                MethodHandle callback = MethodHandles.lookup().findStatic(MacBlock.class, name,
                        signature.toMethodType());
                return Linker.nativeLinker().upcallStub(callback, signature, Arena.global());
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        private static MemorySegment descriptor() {
            MemorySegment descriptor = Arena.global().allocate(32, 8);
            descriptor.set(ValueLayout.JAVA_LONG, 8, BLOCK_BYTES);
            descriptor.set(ValueLayout.ADDRESS, 16, COPY);
            descriptor.set(ValueLayout.ADDRESS, 24, DISPOSE);
            return descriptor;
        }
    }
}
