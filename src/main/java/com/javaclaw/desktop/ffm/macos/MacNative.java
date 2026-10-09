package com.javaclaw.desktop.ffm.macos;

import java.lang.foreign.Arena;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Typed FFM calls to the system Objective-C runtime and Apple frameworks. */
final class MacNative {
    static final AddressLayout ADDRESS = ValueLayout.ADDRESS;
    static final MemoryLayout POINT = MemoryLayout.structLayout(ValueLayout.JAVA_DOUBLE,
            ValueLayout.JAVA_DOUBLE);
    static final MemoryLayout RECT = MemoryLayout.structLayout(POINT, POINT);
    private final List<SymbolLookup> libraries = new ArrayList<>();
    private final Map<String, MethodHandle> functions = new ConcurrentHashMap<>();
    private final Map<String, MemorySegment> selectors = new ConcurrentHashMap<>();

    MacNative() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac"))
            throw new UnsupportedOperationException("macOS system APIs are unavailable on this OS");
        try {
            if (Integer.parseInt(System.getProperty("os.version", "0").split("\\.")[0]) < 14)
                throw new UnsupportedOperationException("ScreenCaptureKit desktop support requires macOS 14 or newer");
        } catch (NumberFormatException unknownVersion) {
            throw new UnsupportedOperationException("Cannot verify macOS version", unknownVersion);
        }
        for (String path : List.of("/usr/lib/libobjc.A.dylib", "/usr/lib/libSystem.B.dylib",
                "/usr/lib/libproc.dylib",
                "/System/Library/Frameworks/Foundation.framework/Foundation",
                "/System/Library/Frameworks/AppKit.framework/AppKit",
                "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
                "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics",
                "/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices",
                "/System/Library/Frameworks/ScreenCaptureKit.framework/ScreenCaptureKit"))
            libraries.add(SymbolLookup.libraryLookup(path, Arena.global()));
    }

    MemorySegment symbol(String name) {
        for (SymbolLookup library : libraries) {
            var found = library.find(name);
            if (found.isPresent()) return found.get();
        }
        throw new UnsatisfiedLinkError("Missing macOS system symbol " + name);
    }

    Object call(String name, MemoryLayout result, MemoryLayout[] arguments, Object... values) {
        FunctionDescriptor descriptor = result == null ? FunctionDescriptor.ofVoid(arguments)
                : FunctionDescriptor.of(result, arguments);
        MethodHandle handle = functions.computeIfAbsent(name + descriptor,
                ignored -> Linker.nativeLinker().downcallHandle(symbol(name), descriptor));
        try { return handle.invokeWithArguments(values); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("macOS call failed: " + name, failure); }
    }

    MemorySegment cls(String name) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) call("objc_getClass", ADDRESS, new MemoryLayout[]{ADDRESS},
                    arena.allocateFrom(name));
        }
    }

    MemorySegment sel(String name) {
        return selectors.computeIfAbsent(name, key -> {
            try (Arena arena = Arena.ofConfined()) {
                return (MemorySegment) call("sel_registerName", ADDRESS, new MemoryLayout[]{ADDRESS},
                        arena.allocateFrom(key));
            }
        });
    }

    Object message(MemorySegment receiver, String selector, MemoryLayout result,
            MemoryLayout[] argumentTypes, Object... arguments) {
        MemoryLayout[] types = new MemoryLayout[argumentTypes.length + 2];
        types[0] = ADDRESS; types[1] = ADDRESS;
        System.arraycopy(argumentTypes, 0, types, 2, argumentTypes.length);
        Object[] values = new Object[arguments.length + 2];
        values[0] = receiver; values[1] = sel(selector);
        System.arraycopy(arguments, 0, values, 2, arguments.length);
        return call("objc_msgSend", result, types, values);
    }

    MemorySegment object(MemorySegment receiver, String selector, Object... arguments) {
        return (MemorySegment) message(receiver, selector, ADDRESS, infer(arguments), arguments);
    }

    long number(MemorySegment receiver, String selector) {
        return (long) message(receiver, selector, ValueLayout.JAVA_LONG, new MemoryLayout[0]);
    }

    int integer(MemorySegment receiver, String selector) {
        return (int) message(receiver, selector, ValueLayout.JAVA_INT, new MemoryLayout[0]);
    }

    boolean bool(MemorySegment receiver, String selector, Object... arguments) {
        return ((byte) message(receiver, selector, ValueLayout.JAVA_BYTE,
                infer(arguments), arguments)) != 0;
    }

    void send(MemorySegment receiver, String selector, Object... arguments) {
        message(receiver, selector, null, infer(arguments), arguments);
    }

    double[] rect(MemorySegment receiver, String selector) {
        String send = System.getProperty("os.arch").equals("x86_64")
                || System.getProperty("os.arch").equals("amd64") ? "objc_msgSend_stret" : "objc_msgSend";
        FunctionDescriptor descriptor = FunctionDescriptor.of(RECT, ADDRESS, ADDRESS);
        MethodHandle handle = functions.computeIfAbsent(send + descriptor,
                ignored -> Linker.nativeLinker().downcallHandle(symbol(send), descriptor));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = (MemorySegment) handle.invokeWithArguments(arena, receiver, sel(selector));
            return new double[]{result.get(ValueLayout.JAVA_DOUBLE, 0),
                    result.get(ValueLayout.JAVA_DOUBLE, 8), result.get(ValueLayout.JAVA_DOUBLE, 16),
                    result.get(ValueLayout.JAVA_DOUBLE, 24)};
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("macOS geometry call failed", failure); }
    }

    double[] functionRect(String name, MemoryLayout[] types, Object... arguments) {
        FunctionDescriptor descriptor = FunctionDescriptor.of(RECT, types);
        MethodHandle handle = functions.computeIfAbsent(name + descriptor,
                ignored -> Linker.nativeLinker().downcallHandle(symbol(name), descriptor));
        try (Arena arena = Arena.ofConfined()) {
            Object[] values = new Object[arguments.length + 1];
            values[0] = arena;
            System.arraycopy(arguments, 0, values, 1, arguments.length);
            MemorySegment rect = (MemorySegment) handle.invokeWithArguments(values);
            return new double[]{rect.get(ValueLayout.JAVA_DOUBLE, 0), rect.get(ValueLayout.JAVA_DOUBLE, 8),
                    rect.get(ValueLayout.JAVA_DOUBLE, 16), rect.get(ValueLayout.JAVA_DOUBLE, 24)};
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("macOS rectangle call failed", failure); }
    }

    MemorySegment string(String text) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment characters = arena.allocateFrom(ValueLayout.JAVA_CHAR, text.toCharArray());
            return object(cls("NSString"), "stringWithCharacters:length:", characters, (long) text.length());
        }
    }

    String text(MemorySegment string) {
        if (nil(string)) return "";
        long length = number(string, "length");
        if (length <= 0) return "";
        if (length > 65_536) throw new IllegalStateException("macOS string exceeds its safe bound");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment characters = arena.allocate(ValueLayout.JAVA_CHAR, length);
            send(string, "getCharacters:", characters);
            return new String(characters.toArray(ValueLayout.JAVA_CHAR));
        }
    }

    List<MemorySegment> array(MemorySegment array, int limit) {
        if (nil(array)) return List.of();
        int count = (int) Math.min(limit, number(array, "count"));
        List<MemorySegment> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(object(array, "objectAtIndex:", (long) i));
        return values;
    }

    MemorySegment attribute(MemorySegment element, String name) {
        // AX reads of this process synchronously enter AppKit/Glass. Glass uses the
        // Cocoa main thread's cached JNIEnv, which is invalid on our native worker.
        // Verify the element owner before any attribute lookup, including focus restoration.
        if (!MacTargetPolicy.externalProcess(accessibilityOwner(element))) return MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int status = (int) call("AXUIElementCopyAttributeValue", ValueLayout.JAVA_INT,
                    new MemoryLayout[]{ADDRESS, ADDRESS, ADDRESS}, element, string(name), out);
            return status == 0 ? out.get(ADDRESS, 0) : MemorySegment.NULL;
        }
    }

    long accessibilityOwner(MemorySegment element) {
        if (nil(element)) return 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pid = arena.allocate(ValueLayout.JAVA_INT);
            int status = (int) call("AXUIElementGetPid", ValueLayout.JAVA_INT,
                    new MemoryLayout[]{ADDRESS, ADDRESS}, element, pid);
            return status == 0 ? Integer.toUnsignedLong(pid.get(ValueLayout.JAVA_INT, 0)) : 0;
        }
    }

    MemorySegment retain(MemorySegment value) {
        if (!nil(value)) call("CFRetain", ADDRESS, new MemoryLayout[]{ADDRESS}, value);
        return value;
    }

    void release(MemorySegment value) {
        if (!nil(value)) call("CFRelease", null, new MemoryLayout[]{ADDRESS}, value);
    }

    Pool pool() {
        requirePlatformThread();
        return new Pool(this, object(cls("NSAutoreleasePool"), "new"), Thread.currentThread());
    }

    static void requirePlatformThread() {
        if (Thread.currentThread().isVirtual())
            throw new IllegalStateException("macOS native operations require one platform thread for their entire lifetime");
    }
    static boolean nil(MemorySegment pointer) { return pointer == null || pointer.address() == 0; }

    private static MemoryLayout[] infer(Object[] arguments) {
        MemoryLayout[] layouts = new MemoryLayout[arguments.length];
        for (int i = 0; i < arguments.length; i++) layouts[i] = switch (arguments[i]) {
            case MemorySegment ignored -> ADDRESS;
            case Long ignored -> ValueLayout.JAVA_LONG;
            case Integer ignored -> ValueLayout.JAVA_INT;
            case Byte ignored -> ValueLayout.JAVA_BYTE;
            case Float ignored -> ValueLayout.JAVA_FLOAT;
            case Double ignored -> ValueLayout.JAVA_DOUBLE;
            default -> throw new IllegalArgumentException("Unsupported macOS argument type");
        };
        return layouts;
    }

    static final class Pool implements AutoCloseable {
        private final MacNative nativeApi;
        private final MemorySegment pointer;
        private final Thread owner;
        private boolean closed;

        Pool(MacNative nativeApi, MemorySegment pointer, Thread owner) {
            this.nativeApi = nativeApi; this.pointer = pointer; this.owner = owner;
        }

        @Override public void close() {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("A macOS autorelease pool must be drained by its creating platform thread");
            if (!closed) { closed = true; nativeApi.send(pointer, "drain"); }
        }
    }
}
