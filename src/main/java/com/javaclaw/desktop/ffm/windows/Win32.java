package com.javaclaw.desktop.ffm.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Windows x64/ARM64 ABI bindings to OS DLLs only; Windows LONG remains 32 bit. */
final class Win32 {
    static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;
    static final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
    static final java.lang.foreign.AddressLayout P = ValueLayout.ADDRESS;
    private final Arena libraries = Arena.ofAuto();
    private final Map<String, SymbolLookup> lookups = new HashMap<>();
    private final Map<String, MethodHandle> functions = new HashMap<>();
    private final Path systemDirectory;

    Win32() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
            throw new UnsupportedOperationException("Windows system APIs require Windows");
        if (P.byteSize() != 8) throw new UnsupportedOperationException("64-bit Windows is required");
        // kernel32 is a Windows KnownDLL. Ask it for the protected system directory rather than trusting ENV/PATH.
        SymbolLookup kernel = SymbolLookup.libraryLookup("kernel32.dll", libraries);
        lookups.put("kernel32", kernel);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocate(65536, 2);
            MethodHandle getDirectory = Linker.nativeLinker().downcallHandle(kernel.find("GetSystemDirectoryW")
                    .orElseThrow(() -> new UnsatisfiedLinkError("GetSystemDirectoryW")), FunctionDescriptor.of(I, P, I));
            int length = (int) invoke(getDirectory, text, 32768);
            if (length < 1 || length >= 32768) throw new IllegalStateException("Windows system directory is unavailable");
            systemDirectory = Path.of(wideString(text, length));
            if (!systemDirectory.isAbsolute()) throw new IllegalStateException("Windows system directory is not absolute");
        }
    }

    synchronized MethodHandle function(String dll, String name, MemoryLayout result,
            MemoryLayout... arguments) {
        FunctionDescriptor descriptor = result == null ? FunctionDescriptor.ofVoid(arguments)
                : FunctionDescriptor.of(result, arguments);
        String key = dll + ':' + name + descriptor;
        return functions.computeIfAbsent(key, ignored -> {
            SymbolLookup symbols = lookups.computeIfAbsent(dll, library -> {
                Path path = systemDirectory.resolve(library + ".dll");
                return SymbolLookup.libraryLookup(path, libraries);
            });
            return Linker.nativeLinker().downcallHandle(symbols.find(name)
                    .orElseThrow(() -> new UnsatisfiedLinkError("Missing system API " + name)), descriptor);
        });
    }

    int integer(String dll, String name, MemoryLayout[] types, Object... args) {
        return (int) invoke(function(dll, name, I, types), args);
    }

    MemorySegment pointer(String dll, String name, MemoryLayout[] types, Object... args) {
        return (MemorySegment) invoke(function(dll, name, P, types), args);
    }

    void nothing(String dll, String name, MemoryLayout[] types, Object... args) {
        invoke(function(dll, name, null, types), args);
    }

    static Object invoke(MethodHandle handle, Object... args) {
        try { return handle.invokeWithArguments(args); }
        catch (RuntimeException | Error failure) { throw failure; }
        catch (Throwable failure) { throw new IllegalStateException("Windows system call failed", failure); }
    }

    int com(MemorySegment object, int slot, MemoryLayout[] types, Object... arguments) {
        return (int) comCall(object, slot, I, types, arguments);
    }

    void comVoid(MemorySegment object, int slot, MemoryLayout[] types, Object... arguments) {
        comCall(object, slot, null, types, arguments);
    }

    private Object comCall(MemorySegment object, int slot, MemoryLayout result, MemoryLayout[] types, Object... arguments) {
        MemoryLayout[] all = new MemoryLayout[types.length + 1];
        all[0] = P;
        System.arraycopy(types, 0, all, 1, types.length);
        Object[] args = new Object[arguments.length + 1];
        args[0] = object;
        System.arraycopy(arguments, 0, args, 1, arguments.length);
        MemorySegment table = object.reinterpret(8).get(P, 0);
        MemorySegment function = table.reinterpret((slot + 1L) * 8).get(P, slot * 8L);
        return invoke(Linker.nativeLinker().downcallHandle(function, result == null
                ? FunctionDescriptor.ofVoid(all) : FunctionDescriptor.of(result, all)), args);
    }

    void release(MemorySegment object) {
        if (!nullPointer(object)) com(object, 2, new MemoryLayout[0]);
    }

    static MemorySegment handle(long value) { return MemorySegment.ofAddress(value); }
    static boolean nullPointer(MemorySegment value) { return value == null || value.address() == 0; }

    static MemorySegment wide(Arena arena, String text) {
        MemorySegment value = arena.allocate((text.length() + 1L) * 2, 2);
        for (int index = 0; index < text.length(); index++) value.set(ValueLayout.JAVA_CHAR, index * 2L, text.charAt(index));
        return value;
    }

    static String wideString(MemorySegment address, int maximum) {
        if (nullPointer(address)) return "";
        MemorySegment value = address.reinterpret(maximum * 2L);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < maximum; index++) {
            char ch = value.get(ValueLayout.JAVA_CHAR, index * 2L);
            if (ch == 0) break;
            result.append(ch);
        }
        return result.toString();
    }

    static MemorySegment guid(Arena arena, String value) {
        UUID uuid = UUID.fromString(value);
        MemorySegment result = arena.allocate(16, 4);
        result.set(I, 0, (int) (uuid.getMostSignificantBits() >>> 32));
        result.set(ValueLayout.JAVA_SHORT, 4, (short) (uuid.getMostSignificantBits() >>> 16));
        result.set(ValueLayout.JAVA_SHORT, 6, (short) uuid.getMostSignificantBits());
        for (int index = 0; index < 8; index++)
            result.set(ValueLayout.JAVA_BYTE, 8 + index, (byte) (uuid.getLeastSignificantBits() >>> (56 - 8 * index)));
        return result;
    }

    Apartment apartment() { return new Apartment(); }
    final class Apartment implements AutoCloseable {
        private final int status = integer("ole32", "CoInitializeEx", new MemoryLayout[]{P, I}, MemorySegment.NULL, 0);
        Apartment() {
            if (status < 0 && status != 0x80010106)
                throw new IllegalStateException("COM apartment unavailable: " + Integer.toHexString(status));
        }
        @Override public void close() {
            if (status >= 0) nothing("ole32", "CoUninitialize", new MemoryLayout[0]);
        }
    }
}
