package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.UUID;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** 只在两个新建子命名空间内登记固定探针服务；不登记宿主全局服务。 */
final class MacBootstrapIsolationProbe {
    private static final int UNKNOWN_SERVICE = 1102;

    private final MethodHandle checkIn = operation("bootstrap_check_in");
    private final MethodHandle lookup = operation("bootstrap_look_up");
    private final MethodHandle destroy = portOperation("mach_port_destroy");
    private final MethodHandle deallocate = portOperation("mach_port_deallocate");

    private MacBootstrapIsolationProbe() {}

    static void verify(int task, int first, int second, int parent) throws IOException {
        MacBootstrapIsolationProbe probe = new MacBootstrapIsolationProbe();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment name = arena.allocateFrom("com.javaclaw.namespace-probe." + UUID.randomUUID());
            MemorySegment service = arena.allocate(JAVA_INT);
            checked(probe.call(probe.checkIn, first, name, service), "private service check-in");
            int receive = service.get(JAVA_INT, 0);
            try {
                checked(probe.call(probe.lookup, first, name, service), "same namespace lookup");
                checked(probe.call(probe.deallocate, task, service.get(JAVA_INT, 0)), "release lookup right");
                probe.denied(second, name, service, task);
                probe.denied(parent, name, service, task);
            } finally {
                checked(probe.call(probe.destroy, task, receive), "release probe service");
            }
        }
    }

    private void denied(int namespace, MemorySegment name, MemorySegment service, int task) throws IOException {
        int result = call(lookup, namespace, name, service);
        if (result == 0) {
            checked(call(deallocate, task, service.get(JAVA_INT, 0)), "release unexpected lookup right");
            throw new IOException("MAC_BOOTSTRAP_ISOLATION_FAILED: private service was visible outside its namespace");
        }
        if (result != UNKNOWN_SERVICE) {
            throw new IOException("MAC_BOOTSTRAP_ISOLATION_FAILED: lookup returned " + result);
        }
    }

    private int call(MethodHandle operation, Object... arguments) throws IOException {
        try {
            return (int) operation.invokeWithArguments(arguments);
        } catch (Throwable failure) {
            throw new IOException("MAC_BOOTSTRAP_ISOLATION_FAILED: native probe", failure);
        }
    }

    private static MethodHandle operation(String name) {
        Linker linker = Linker.nativeLinker();
        return linker.downcallHandle(
                SymbolLookup.loaderLookup()
                        .or(linker.defaultLookup())
                        .find(name)
                        .orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    }

    private static MethodHandle portOperation(String name) {
        Linker linker = Linker.nativeLinker();
        return linker.downcallHandle(
                linker.defaultLookup().find(name).orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    }

    private static void checked(int result, String operation) throws IOException {
        if (result != 0) {
            throw new IOException("MAC_BOOTSTRAP_ISOLATION_FAILED: " + operation + " returned " + result);
        }
    }
}
