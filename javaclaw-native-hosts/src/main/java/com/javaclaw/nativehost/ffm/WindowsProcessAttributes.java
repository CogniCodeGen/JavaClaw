package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** 拥有并填写一次 CreateProcessAsUserW 所需的 PROC_THREAD_ATTRIBUTE_LIST。 */
final class WindowsProcessAttributes implements AutoCloseable {
    private static final long HANDLE_LIST = 0x00020002L;
    private static final long SECURITY_CAPABILITIES = 0x00020009L;
    private static final long PSEUDOCONSOLE = 0x00020016L;
    private static final int STARTF_USESTDHANDLES = 0x00000100;

    private final MemorySegment list;
    private final Arena arena;

    private WindowsProcessAttributes(MemorySegment list, Arena arena) {
        this.list = list;
        this.arena = arena;
    }

    static WindowsProcessAttributes create(Arena arena, int count) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment size = arena.allocate(JAVA_LONG);
        backend.invoke(backend.initializeAttributeList, MemorySegment.NULL, count, 0, size);
        long bytes = size.get(JAVA_LONG, 0);
        if (bytes < 1 || bytes > 1024 * 1024) {
            throw new IOException("invalid Windows process attribute-list size: " + bytes);
        }
        MemorySegment list = arena.allocate(bytes, ADDRESS.byteAlignment());
        var initialized = backend.invoke(backend.initializeAttributeList, list, count, 0, size);
        if (initialized.number() == 0) {
            throw WindowsSandboxNative.error("InitializeProcThreadAttributeList", initialized.error());
        }
        return new WindowsProcessAttributes(list, arena);
    }

    void addSecurityCapabilities(MemorySegment appContainerSid) throws IOException {
        MemorySegment value = arena.allocate(WindowsSandboxNative.SECURITY_CAPABILITIES);
        value.set(ADDRESS, 0, appContainerSid);
        value.set(ADDRESS, 8, MemorySegment.NULL);
        value.set(JAVA_INT, 16, 0);
        value.set(JAVA_INT, 20, 0);
        update(SECURITY_CAPABILITIES, value, value.byteSize());
    }

    void addHandleList(List<MemorySegment> handles) throws IOException {
        MemorySegment value = arena.allocate(ADDRESS.byteSize() * handles.size(), ADDRESS.byteAlignment());
        for (int index = 0; index < handles.size(); index++) {
            value.setAtIndex(ADDRESS, index, handles.get(index));
        }
        update(HANDLE_LIST, value, value.byteSize());
    }

    void addPseudoConsole(MemorySegment pseudoConsole) throws IOException {
        update(PSEUDOCONSOLE, pseudoConsole, ADDRESS.byteSize());
    }

    MemorySegment startup(List<MemorySegment> standardHandles) {
        MemorySegment startup = arena.allocate(WindowsSandboxNative.STARTUP_INFO_EX);
        startup.set(JAVA_INT, 0, Math.toIntExact(WindowsSandboxNative.STARTUP_INFO_EX.byteSize()));
        if (!standardHandles.isEmpty()) {
            if (standardHandles.size() != 3) {
                throw new IllegalArgumentException("three standard handles are required");
            }
            startup.set(JAVA_INT, 60, STARTF_USESTDHANDLES);
            startup.set(ADDRESS, 80, standardHandles.get(0));
            startup.set(ADDRESS, 88, standardHandles.get(1));
            startup.set(ADDRESS, 96, standardHandles.get(2));
        }
        startup.set(ADDRESS, 104, list);
        return startup;
    }

    @Override
    public void close() {
        WindowsSandboxNative.deleteAttributeList(list);
    }

    private void update(long attribute, MemorySegment value, long bytes) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        var result = backend.invoke(
                backend.updateAttribute, list, 0, attribute, value, bytes, MemorySegment.NULL, MemorySegment.NULL);
        if (result.number() == 0) {
            throw WindowsSandboxNative.error("UpdateProcThreadAttribute", result.error());
        }
    }
}
