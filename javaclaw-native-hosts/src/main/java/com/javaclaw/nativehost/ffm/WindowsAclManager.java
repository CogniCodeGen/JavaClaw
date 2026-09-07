package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
        // cwd 本身只需遍历；窄 readRoots 不应因进程工作目录而获得其余文件的读取权限。
        merge(grants, request.workingDirectory(), new Grant(0x20 | 0x100000, 0));
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
                throw restorationFailure(failure);
            }
            throw failure;
        }
    }

    static IOException restore(List<Snapshot> snapshots) {
        IOException failure = null;
        for (int index = snapshots.size() - 1; index >= 0; index--) {
            try {
                restore(snapshots.get(index));
            } catch (IOException | RuntimeException current) {
                IOException cleanup =
                        current instanceof IOException io ? io : new IOException("DACL restore failed", current);
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        return failure;
    }

    private static void merge(Map<Path, Grant> grants, Path path, Grant value) {
        grants.merge(path, value, Grant::merge);
    }

    private static Snapshot change(Path path, MemorySegment sid, Grant grant) throws IOException {
        WindowsFileHandle handle = WindowsFileHandle.openPath(
                path,
                WindowsFileHandle.READ_CONTROL | WindowsFileHandle.WRITE_DAC | WindowsFileHandle.ATTRIBUTES,
                WindowsFileHandle.ANY);
        Snapshot snapshot = null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment daclOut = arena.allocate(ADDRESS);
            MemorySegment descriptor = readDescriptor(handle, daclOut, arena);
            try {
                boolean protectedDacl = protectedDacl(descriptor, arena);
                String sddl = descriptorSddl(descriptor, arena);
                var evidence = WindowsAclEvidence.capture(handle, path, sddl, protectedDacl);
                snapshot = new Snapshot(handle, sddl, protectedDacl, evidence);
                MemorySegment updated = addEntries(handle.directory(), sid, grant, daclOut.get(ADDRESS, 0), arena);
                try {
                    setDacl(handle, updated, protectedDacl);
                } finally {
                    WindowsSandboxNative.localFree(updated);
                }
                return snapshot;
            } finally {
                WindowsSandboxNative.localFree(descriptor);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                if (snapshot == null) {
                    handle.close();
                } else {
                    restore(snapshot);
                }
            } catch (IOException | RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
                throw restorationFailure(failure);
            }
            throw failure;
        }
    }

    private static WindowsSandbox.AclRestorationException restorationFailure(Throwable cause) {
        return new WindowsSandbox.AclRestorationException(
                "Windows Sandbox partial ACL restoration failed; lock the Workspace", cause);
    }

    private static MemorySegment readDescriptor(WindowsFileHandle handle, MemorySegment daclOut, Arena arena)
            throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        MemorySegment descriptorOut = arena.allocate(ADDRESS);
        var current = backend.invoke(
                backend.getSecurityInfo,
                handle.address(),
                SE_FILE_OBJECT,
                DACL_SECURITY_INFORMATION,
                MemorySegment.NULL,
                MemorySegment.NULL,
                daclOut,
                MemorySegment.NULL,
                descriptorOut);
        if (current.number() != 0) {
            throw WindowsSandboxNative.status("GetSecurityInfo", current.number());
        }
        return descriptorOut.get(ADDRESS, 0);
    }

    private static MemorySegment addEntries(
            boolean directory, MemorySegment sid, Grant grant, MemorySegment currentDacl, Arena arena)
            throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        var policy = WindowsFileAclPolicy.entries(directory, grant.allowed(), grant.denied());
        int count = policy.size();
        MemorySegment entries = arena.allocate(
                WindowsSandboxNative.EXPLICIT_ACCESS.byteSize() * count,
                WindowsSandboxNative.EXPLICIT_ACCESS.byteAlignment());
        for (int index = 0; index < count; index++) {
            fillEntry(entries, index, sid, policy.get(index));
        }
        MemorySegment aclOut = arena.allocate(ADDRESS);
        var result = backend.invoke(backend.setEntriesInAcl, count, entries, currentDacl, aclOut);
        if (result.number() != 0) {
            throw WindowsSandboxNative.status("SetEntriesInAclW", result.number());
        }
        return aclOut.get(ADDRESS, 0);
    }

    private static void fillEntry(
            MemorySegment entries, int index, MemorySegment sid, WindowsFileAclPolicy.Entry policy) {
        long offset = WindowsSandboxNative.EXPLICIT_ACCESS.byteSize() * index;
        MemorySegment entry = entries.asSlice(offset, WindowsSandboxNative.EXPLICIT_ACCESS.byteSize());
        entry.set(JAVA_INT, 0, policy.permissions());
        entry.set(JAVA_INT, 4, policy.mode());
        entry.set(JAVA_INT, 8, policy.inheritance());
        entry.set(ADDRESS, 16, MemorySegment.NULL);
        entry.set(JAVA_INT, 24, 0);
        entry.set(JAVA_INT, 28, TRUSTEE_IS_SID);
        entry.set(JAVA_INT, 32, TRUSTEE_IS_UNKNOWN);
        entry.set(ADDRESS, 40, sid);
    }

    private static void setDacl(WindowsFileHandle handle, MemorySegment dacl, boolean protectedDacl)
            throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        var result = backend.invoke(
                backend.setSecurityInfo,
                handle.address(),
                SE_FILE_OBJECT,
                daclFlags(protectedDacl),
                MemorySegment.NULL,
                MemorySegment.NULL,
                dacl,
                MemorySegment.NULL);
        if (result.number() != 0) {
            throw WindowsSandboxNative.status("SetSecurityInfo", result.number());
        }
    }

    private static void restore(Snapshot snapshot) throws IOException {
        if (!snapshot.restored.compareAndSet(false, true)) {
            return;
        }
        IOException failure = null;
        try (WindowsFileHandle handle = snapshot.handle) {
            restoreDacl(snapshot, handle);
        } catch (IOException | RuntimeException current) {
            failure = current instanceof IOException io ? io : new IOException("DACL restore failed", current);
        }
        try {
            snapshot.evidence.finish(failure);
        } catch (IOException evidenceFailure) {
            if (failure == null) {
                failure = evidenceFailure;
            } else {
                failure.addSuppressed(evidenceFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void restoreDacl(Snapshot snapshot, WindowsFileHandle handle) throws IOException {
        var backend = WindowsSandboxNative.requireBackend();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment descriptorOut = arena.allocate(ADDRESS);
            var decoded = backend.invoke(
                    backend.sddlToDescriptor,
                    WindowsSandboxNative.wide(arena, snapshot.sddl),
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
                setDacl(handle, daclOut.get(ADDRESS, 0), snapshot.protectedDacl);
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

    /** Snapshot 独占句柄；原始 DACL、授权和恢复始终指向同一对象，恢复至多尝试一次。 */
    static final class Snapshot {
        private final WindowsFileHandle handle;
        private final String sddl;
        private final boolean protectedDacl;
        private final AtomicBoolean restored = new AtomicBoolean();
        private final WindowsAclEvidence.Ticket evidence;

        private Snapshot(
                WindowsFileHandle handle, String sddl, boolean protectedDacl, WindowsAclEvidence.Ticket evidence) {
            this.handle = handle;
            this.sddl = sddl;
            this.protectedDacl = protectedDacl;
            this.evidence = evidence;
        }
    }

    private record Grant(int allowed, int denied) {
        private Grant merge(Grant other) {
            return new Grant(allowed | other.allowed, denied | other.denied);
        }
    }
}
