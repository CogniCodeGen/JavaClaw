package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** 独占原生句柄的生命周期；不共享 DELETE，拒绝 reparse，关闭时逆序释放祖先固定句柄。 */
final class WindowsFileHandle implements AutoCloseable {
    static final int ATTRIBUTES = 0x80;
    static final int READ_CONTROL = 0x20000;
    static final int WRITE_DAC = 0x40000;
    static final int DELETE = 0x10000;
    static final int DIRECTORY = 1;
    static final int FILE = 0x40;
    static final int ANY = 0;
    static final int OPEN = 1;
    static final int CREATE = 2;
    private static final int SYNCHRONIZE = 0x100000;
    private final MemorySegment handle;
    private final boolean directory;
    private final List<WindowsFileHandle> ancestors = new ArrayList<>();
    private Object listingLock = new Object();
    private boolean closed;

    private WindowsFileHandle(MemorySegment handle, boolean directory) {
        this.handle = WindowsSandboxNative.global(handle);
        this.directory = directory;
    }

    static WindowsFileHandle openPath(Path path, int access, int kind) throws IOException {
        if (!path.isAbsolute() || !path.normalize().equals(path)) {
            throw new IOException("Windows object path must be absolute and normalized");
        }
        ArrayList<WindowsFileHandle> opened = new ArrayList<>();
        try {
            Path root = path.getRoot();
            boolean rootOnly = path.equals(root);
            WindowsFileHandle current = volumeRoot(root, rootOnly ? access : ATTRIBUTES);
            opened.add(current);
            for (int index = 0; index < path.getNameCount(); index++) {
                boolean last = index == path.getNameCount() - 1;
                current = current.child(
                        path.getName(index).toString(), last ? access : ATTRIBUTES, last ? kind : DIRECTORY, OPEN);
                opened.add(current);
            }
            opened.removeLast();
            current.ancestors.addAll(opened);
            return current;
        } catch (IOException | RuntimeException failure) {
            closeAll(opened, failure);
            throw failure;
        }
    }

    private static WindowsFileHandle volumeRoot(Path root, int access) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            var result = WindowsFileNative.call(
                    WindowsFileNative.backend().createFile,
                    WindowsSandboxNative.wide(arena, root.toString()),
                    access | SYNCHRONIZE,
                    3,
                    MemorySegment.NULL,
                    3,
                    0x02200000,
                    MemorySegment.NULL);
            WindowsSandboxNative.requireHandle(result.address(), "open volume root", result.error());
            return checked(result.address(), DIRECTORY, true);
        }
    }

    synchronized WindowsFileHandle child(String name, int access, int kind, int disposition) throws IOException {
        requireLeaf(name);
        return openNative(address(), name, access, kind, disposition);
    }

    static WindowsFileHandle openRoot(Path root, int access) throws IOException {
        return openNative(MemorySegment.NULL, WindowsWorkspaceRoot.ntPath(root), access, DIRECTORY, OPEN);
    }

    synchronized WindowsFileHandle reopenDirectory(int access) throws IOException {
        return openNative(address(), "", access, DIRECTORY, OPEN);
    }

    private static WindowsFileHandle openNative(
            MemorySegment parent, String name, int access, int kind, int disposition) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment object = objectAttributes(arena, parent, name);
            MemorySegment result = arena.allocate(ADDRESS);
            MemorySegment status = arena.allocate(16, 8);
            int code = WindowsFileNative.call(
                            WindowsFileNative.backend().ntCreate,
                            result,
                            access | (kind == DIRECTORY ? 0 : ATTRIBUTES) | SYNCHRONIZE,
                            object,
                            status,
                            MemorySegment.NULL,
                            0x80,
                            3,
                            disposition,
                            kind | 0x20 | (kind == DIRECTORY ? 0 : 0x00200000),
                            MemorySegment.NULL,
                            0)
                    .number();
            WindowsFileNative.ntCheck("NtCreateFile", name, code);
            MemorySegment opened = result.get(ADDRESS, 0);
            WindowsSandboxNative.requireHandle(opened, "NtCreateFile", code);
            return checked(opened, kind, kind != DIRECTORY || (access & ATTRIBUTES) != 0);
        }
    }

    private static MemorySegment objectAttributes(Arena arena, MemorySegment parent, String name) {
        if (name.length() > 32_766) {
            throw new IllegalArgumentException("Windows object name exceeds UNICODE_STRING capacity");
        }
        MemorySegment text = WindowsSandboxNative.wide(arena, name);
        MemorySegment unicode = arena.allocate(16, 8);
        unicode.set(JAVA_SHORT, 0, (short) (name.length() * 2));
        unicode.set(JAVA_SHORT, 2, (short) ((name.length() + 1) * 2));
        unicode.set(ADDRESS, 8, text);
        MemorySegment object = arena.allocate(48, 8);
        object.set(JAVA_INT, 0, 48);
        object.set(ADDRESS, 8, parent);
        object.set(ADDRESS, 16, unicode);
        // OBJ_DONT_REPARSE 避免解析过程进入 junction；最终对象也以句柄属性拒绝 reparse。
        object.set(JAVA_INT, 24, 0x1040);
        return object;
    }

    static void requireLeaf(String name) {
        if (name == null
                || name.isEmpty()
                || name.length() > 255
                || name.equals(".")
                || name.equals("..")
                || forbiddenLeafForm(name)) {
            throw new IllegalArgumentException("Windows entry must be a single ordinary relative name");
        }
    }

    private static boolean forbiddenLeafForm(String name) {
        return name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || name.indexOf(':') >= 0
                || name.endsWith(".")
                || name.endsWith(" ")
                || name.chars().anyMatch(value -> value < 32);
    }

    private static WindowsFileHandle checked(MemorySegment handle, int kind, boolean queryAttributes)
            throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            if (WindowsFileNative.call(WindowsFileNative.backend().fileType, handle)
                            .number()
                    != 1) {
                throw new IOException("Windows workspace object must be a disk file");
            }
            // 纯遍历能力没有 READ_ATTRIBUTES；FILE_DIRECTORY_FILE 和 OBJ_DONT_REPARSE 已由内核校验类型。
            if (!queryAttributes) {
                return new WindowsFileHandle(handle, true);
            }
            MemorySegment info = information(handle, arena);
            int attributes = info.get(JAVA_INT, 0);
            boolean directory = (attributes & 0x10) != 0;
            if ((attributes & 0x440) != 0 || (kind == DIRECTORY && !directory) || (kind == FILE && directory)) {
                throw new IOException("Windows workspace object has an unsafe or unexpected file type");
            }
            return new WindowsFileHandle(handle, directory);
        } catch (IOException | RuntimeException failure) {
            WindowsSandboxNative.closeHandleQuietly(handle);
            throw failure;
        }
    }

    static MemorySegment information(MemorySegment handle, Arena arena) throws IOException {
        MemorySegment information = arena.allocate(52, 4);
        WindowsFileNative.requireSuccess(
                WindowsFileNative.backend().fileInformation, "GetFileInformationByHandle", handle, information);
        return information;
    }

    synchronized WindowsFileHandle duplicate() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            var backend = WindowsSandboxNative.requireBackend();
            MemorySegment process = backend.invokePlainAddress(backend.getCurrentProcess);
            MemorySegment result = arena.allocate(ADDRESS);
            WindowsFileNative.requireSuccess(
                    WindowsFileNative.backend().duplicate,
                    "DuplicateHandle",
                    process,
                    address(),
                    process,
                    result,
                    0,
                    0,
                    2);
            WindowsFileHandle duplicate = new WindowsFileHandle(result.get(ADDRESS, 0), directory);
            duplicate.listingLock = listingLock;
            return duplicate;
        }
    }

    synchronized MemorySegment address() throws ClosedChannelException {
        if (closed) {
            throw new ClosedChannelException();
        }
        return handle;
    }

    boolean directory() {
        return directory;
    }

    Object listingLock() {
        return listingLock;
    }

    synchronized boolean isOpen() {
        return !closed;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            WindowsSandboxNative.closeHandle(handle);
        } catch (IOException current) {
            failure = current;
        }
        for (int index = ancestors.size() - 1; index >= 0; index--) {
            try {
                ancestors.get(index).close();
            } catch (IOException current) {
                if (failure == null) {
                    failure = current;
                } else {
                    failure.addSuppressed(current);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void closeAll(List<WindowsFileHandle> opened, Throwable failure) {
        for (int index = opened.size() - 1; index >= 0; index--) {
            try {
                opened.get(index).close();
            } catch (IOException current) {
                failure.addSuppressed(current);
            }
        }
    }
}
