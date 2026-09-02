package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** 临时授予唯一 AppContainer SID 最小文件权限，并按逆序恢复原始 DACL。 */
final class WindowsAclManager {
    private static final int SE_FILE_OBJECT = 1;
    private static final int DACL_SECURITY_INFORMATION = 0x00000004;
    private static final int PROTECTED_DACL_SECURITY_INFORMATION = 0x80000000;
    private static final int UNPROTECTED_DACL_SECURITY_INFORMATION = 0x20000000;
    private static final int SE_DACL_PROTECTED = 0x1000;
    private static final int SDDL_REVISION_1 = 1;
    private static final int GRANT_ACCESS = 1;
    private static final int DENY_ACCESS = 3;
    private static final int SUB_CONTAINERS_AND_OBJECTS_INHERIT = 3;
    private static final int TRUSTEE_IS_SID = 0;
    private static final int TRUSTEE_IS_UNKNOWN = 0;
    private static final int FILE_GENERIC_READ = 0x00120089;
    private static final int FILE_GENERIC_WRITE = 0x00120116;
    private static final int FILE_GENERIC_EXECUTE = 0x001200a0;
    private static final int DELETE = 0x00010000;
    private static final int FILE_DELETE_CHILD = 0x00000040;

    private WindowsAclManager() {}

    static List<Snapshot> grant(WindowsSandboxPaths.Prepared request, MemorySegment sid) throws IOException {
        LinkedHashMap<Path, Grant> grants = new LinkedHashMap<>();
        int read = FILE_GENERIC_READ | FILE_GENERIC_EXECUTE;
        request.readRoots().forEach(path -> merge(grants, path, new Grant(read, 0)));
        int write = read | FILE_GENERIC_WRITE | (request.allowDelete() ? DELETE : 0);
        int deny = request.allowDelete() ? 0 : DELETE | FILE_DELETE_CHILD;
        request.writeRoots().forEach(path -> merge(grants, path, new Grant(write, deny)));
        merge(grants, request.workingDirectory(), new Grant(read, 0));
        if (!WindowsSandboxPaths.trusted(request.executable())) {
            merge(grants, request.executable(), new Grant(read, 0));
        }
        ArrayList<Snapshot> snapshots = new ArrayList<>();
        try {
            for (Map.Entry<Path, Grant> grant : grants.entrySet()) {
                snapshots.add(change(grant.getKey(), sid, grant.getValue()));
            }
            return List.copyOf(snapshots);
        } catch (IOException | RuntimeException failure) {
            IOException cleanup = restore(snapshots);
            if (cleanup != null) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    static IOException restore(List<Snapshot> snapshots) {
        IOException failure = null;
        for (int index = snapshots.size() - 1; index >= 0; index--) {
            try {
                restore(snapshots.get(index));
            } catch (IOException current) {
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            }
        }
        return failure;
    }

    private static void merge(Map<Path, Grant> grants, Path path, Grant value) {
        grants.merge(path, value, Grant::merge);
    }

    private static Snapshot change(Path path, MemorySegment sid, Grant grant) throws IOException {
        WindowsSandboxPaths.rejectReparsePoint(path);
        var backend = WindowsSandboxNative.requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment daclOut = arena.allocate(ADDRESS);
            MemorySegment descriptorOut = arena.allocate(ADDRESS);
            var current = backend.invoke(
                    backend.getNamedSecurityInfo,
                    WindowsSandboxNative.wide(arena, path.toString()),
                    SE_FILE_OBJECT,
                    DACL_SECURITY_INFORMATION,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    daclOut,
                    MemorySegment.NULL,
                    descriptorOut);
            if (current.number() != 0) {
                throw WindowsSandboxNative.status("GetNamedSecurityInfoW", current.number());
            }
            MemorySegment descriptor = descriptorOut.get(ADDRESS, 0);
            try {
                boolean protectedDacl = protectedDacl(descriptor, arena);
                Snapshot snapshot = new Snapshot(path, descriptorSddl(descriptor, arena), protectedDacl);
                MemorySegment updated = addEntries(path, sid, grant, daclOut.get(ADDRESS, 0), arena);
                try {
                    setDacl(path, updated, protectedDacl, arena);
                } finally {
                    WindowsSandboxNative.localFree(updated);
                }
                return snapshot;
            } finally {
                WindowsSandboxNative.localFree(descriptor);
            }
        }
    }

    private static MemorySegment addEntries(
            Path path, MemorySegment sid, Grant grant, MemorySegment currentDacl, Arena arena) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        int count = grant.denied() == 0 ? 1 : 2;
        MemorySegment entries = arena.allocate(
                WindowsSandboxNative.EXPLICIT_ACCESS.byteSize() * count,
                WindowsSandboxNative.EXPLICIT_ACCESS.byteAlignment());
        fillEntry(entries, 0, path, sid, grant.allowed(), GRANT_ACCESS);
        if (count == 2) {
            fillEntry(entries, 1, path, sid, grant.denied(), DENY_ACCESS);
        }
        MemorySegment aclOut = arena.allocate(ADDRESS);
        var result = backend.invoke(backend.setEntriesInAcl, count, entries, currentDacl, aclOut);
        if (result.number() != 0) {
            throw WindowsSandboxNative.status("SetEntriesInAclW", result.number());
        }
        return aclOut.get(ADDRESS, 0);
    }

    private static void fillEntry(
            MemorySegment entries, int index, Path path, MemorySegment sid, int permissions, int mode) {
        long offset = WindowsSandboxNative.EXPLICIT_ACCESS.byteSize() * index;
        MemorySegment entry = entries.asSlice(offset, WindowsSandboxNative.EXPLICIT_ACCESS.byteSize());
        entry.set(JAVA_INT, 0, permissions);
        entry.set(JAVA_INT, 4, mode);
        entry.set(JAVA_INT, 8, Files.isDirectory(path) ? SUB_CONTAINERS_AND_OBJECTS_INHERIT : 0);
        entry.set(ADDRESS, 16, MemorySegment.NULL);
        entry.set(JAVA_INT, 24, 0);
        entry.set(JAVA_INT, 28, TRUSTEE_IS_SID);
        entry.set(JAVA_INT, 32, TRUSTEE_IS_UNKNOWN);
        entry.set(ADDRESS, 40, sid);
    }

    private static void setDacl(Path path, MemorySegment dacl, boolean protectedDacl, Arena arena) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        var result = backend.invoke(
                backend.setNamedSecurityInfo,
                WindowsSandboxNative.wide(arena, path.toString()),
                SE_FILE_OBJECT,
                daclFlags(protectedDacl),
                MemorySegment.NULL,
                MemorySegment.NULL,
                dacl,
                MemorySegment.NULL);
        if (result.number() != 0) {
            throw WindowsSandboxNative.status("SetNamedSecurityInfoW", result.number());
        }
    }

    private static void restore(Snapshot snapshot) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment descriptorOut = arena.allocate(ADDRESS);
            var decoded = backend.invoke(
                    backend.sddlToDescriptor,
                    WindowsSandboxNative.wide(arena, snapshot.sddl()),
                    SDDL_REVISION_1,
                    descriptorOut,
                    MemorySegment.NULL);
            if (decoded.number() == 0) {
                throw WindowsSandboxNative.error("decode saved DACL", decoded.error());
            }
            MemorySegment descriptor = descriptorOut.get(ADDRESS, 0);
            try {
                MemorySegment present = arena.allocate(JAVA_INT);
                MemorySegment daclOut = arena.allocate(ADDRESS);
                MemorySegment defaulted = arena.allocate(JAVA_INT);
                var dacl = backend.invoke(backend.getSecurityDescriptorDacl, descriptor, present, daclOut, defaulted);
                if (dacl.number() == 0 || present.get(JAVA_INT, 0) == 0) {
                    throw WindowsSandboxNative.error("read saved DACL", dacl.error());
                }
                setDacl(snapshot.path(), daclOut.get(ADDRESS, 0), snapshot.protectedDacl(), arena);
            } finally {
                WindowsSandboxNative.localFree(descriptor);
            }
        }
    }

    private static boolean protectedDacl(MemorySegment descriptor, Arena arena) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment control = arena.allocate(JAVA_SHORT);
        MemorySegment revision = arena.allocate(JAVA_INT);
        var result = backend.invoke(backend.getSecurityDescriptorControl, descriptor, control, revision);
        if (result.number() == 0) {
            throw WindowsSandboxNative.error("GetSecurityDescriptorControl", result.error());
        }
        return (Short.toUnsignedInt(control.get(JAVA_SHORT, 0)) & SE_DACL_PROTECTED) != 0;
    }

    private static String descriptorSddl(MemorySegment descriptor, Arena arena) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment textOut = arena.allocate(ADDRESS);
        var result = backend.invoke(
                backend.descriptorToSddl,
                descriptor,
                SDDL_REVISION_1,
                DACL_SECURITY_INFORMATION,
                textOut,
                MemorySegment.NULL);
        if (result.number() == 0) {
            throw WindowsSandboxNative.error("encode original DACL", result.error());
        }
        MemorySegment text = textOut.get(ADDRESS, 0);
        try {
            return WindowsSandboxNative.readWide(text);
        } finally {
            WindowsSandboxNative.localFree(text);
        }
    }

    private static int daclFlags(boolean protectedDacl) {
        return DACL_SECURITY_INFORMATION
                | (protectedDacl ? PROTECTED_DACL_SECURITY_INFORMATION : UNPROTECTED_DACL_SECURITY_INFORMATION);
    }

    record Snapshot(Path path, String sddl, boolean protectedDacl) {}

    private record Grant(int allowed, int denied) {
        private Grant merge(Grant other) {
            return new Grant(allowed | other.allowed, denied | other.denied);
        }
    }
}
