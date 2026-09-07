package com.javaclaw.nativehost.ffm;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Windows Workspace 的目录能力。首次打开仅接受本地真实卷，后续操作以 NtCreateFile RootDirectory 和 OBJ_DONT_REPARSE 解析单个名称，不通过展示路径重新访问文件。
 * 每次操作持有独立句柄引用，close 不使正在进行的原生调用使用已释放句柄。
 */
public final class WindowsWorkspaceDirectory implements AutoCloseable {
    private static final int DIRECTORY_ACCESS = 0x20;
    private final WindowsFileHandle handle;

    private WindowsWorkspaceDirectory(WindowsFileHandle handle) {
        this.handle = handle;
    }

    /** 打开已经冻结的绝对规范目录；拒绝路径中的 reparse、UNC 和转发盘符。 返回值独占目录句柄，调用方必须关闭；不要求 Worker 取得每级祖先的读取权限。 */
    public static WindowsWorkspaceDirectory open(Path root) throws IOException {
        return new WindowsWorkspaceDirectory(WindowsFileHandle.openRoot(root, DIRECTORY_ACCESS));
    }

    /** 按相对目录逐级取得新能力；空串复制当前能力，拒绝绝对路径、父级及重解析点。 */
    public WindowsWorkspaceDirectory directory(String relative) throws IOException {
        Objects.requireNonNull(relative, "relative");
        WindowsFileHandle current = handle.duplicate();
        try {
            if (!relative.isEmpty()) {
                for (String name : relative.split("/", -1)) {
                    WindowsFileHandle next =
                            current.child(name, DIRECTORY_ACCESS, WindowsFileHandle.DIRECTORY, WindowsFileHandle.OPEN);
                    try {
                        current.close();
                    } catch (IOException failure) {
                        closeAfterFailure(next, failure);
                        throw failure;
                    }
                    current = next;
                }
            }
            return new WindowsWorkspaceDirectory(current);
        } catch (IOException | RuntimeException failure) {
            closeAfterFailure(current, failure);
            throw failure;
        }
    }

    /** 读取单个普通文件或目录的同句柄属性；缺失返回 NoSuchFileException，不接受链接。 */
    public BasicFileAttributes attributes(String leaf) throws IOException {
        try (WindowsFileHandle child =
                handle.child(leaf, WindowsFileHandle.ATTRIBUTES, WindowsFileHandle.ANY, WindowsFileHandle.OPEN)) {
            return WindowsWorkspaceAttributes.read(child);
        }
    }

    /** 枚举至多 maximum 个直接子项名称；超过限制抛出异常，不把截断结果伪装成完整列表。 */
    public List<String> names(int maximum) throws IOException {
        if (maximum < 1 || maximum > 10_000) {
            throw new IllegalArgumentException("Windows directory entry limit must be 1..10000");
        }
        return WindowsWorkspaceListing.names(handle, maximum);
    }

    /** 打开单个普通文件的有界同步通道，支持 READ、WRITE、CREATE、CREATE_NEW 与 TRUNCATE_EXISTING。 创建继承父目录 DACL；所有模式禁止重解析，未知选项拒绝而不降级。 */
    public SeekableByteChannel openFile(String leaf, Set<? extends OpenOption> options) throws IOException {
        OpenMode mode = OpenMode.parse(options);
        WindowsFileHandle file = handle.child(leaf, mode.access(), WindowsFileHandle.FILE, mode.disposition());
        WindowsWorkspaceChannel channel = new WindowsWorkspaceChannel(file, mode.read(), mode.write());
        try {
            if (mode.truncate()) {
                channel.truncate(0);
            }
            return channel;
        } catch (IOException | RuntimeException failure) {
            closeAfterFailure(file, failure);
            throw failure;
        }
    }

    /** 原子创建一个直接子目录；名称已存在时失败，不跟随或复用已有对象。 */
    public void createDirectory(String leaf) throws IOException {
        try (WindowsFileHandle created =
                handle.child(leaf, DIRECTORY_ACCESS, WindowsFileHandle.DIRECTORY, WindowsFileHandle.CREATE)) {
            created.address();
        }
    }

    /** 通过源文件句柄及目标目录句柄执行原子不覆盖重命名；目标存在或跨卷时失败。 不先检查目标存在性，也不使用路径检查后替换的实现。 */
    public void moveNoReplace(String leaf, WindowsWorkspaceDirectory target, String targetLeaf) throws IOException {
        WindowsFileHandle.requireLeaf(targetLeaf);
        try (WindowsFileHandle source =
                        handle.child(leaf, WindowsFileHandle.DELETE, WindowsFileHandle.ANY, WindowsFileHandle.OPEN);
                WindowsFileHandle destination = target.handle.duplicate();
                Arena arena = Arena.ofConfined()) {
            MemorySegment text = WindowsSandboxNative.wide(arena, targetLeaf);
            int bytes = targetLeaf.length() * 2;
            MemorySegment rename = arena.allocate(24L + bytes, 8);
            rename.set(JAVA_BYTE, 0, (byte) 0);
            rename.set(ADDRESS, 8, destination.address());
            rename.set(JAVA_INT, 16, bytes);
            MemorySegment.copy(text, 0, rename, 20, bytes);
            setInformation(source, rename, 24 + bytes, 10, "rename workspace entry", leaf);
        }
    }

    /** 删除单个普通文件；通过已验证句柄设置 disposition，拒绝目录及链接。 */
    public void deleteFile(String leaf) throws IOException {
        delete(leaf, WindowsFileHandle.FILE);
    }

    /** 删除单个空目录；非空目录、链接或权限不足均失败，不递归删除。 */
    public void deleteDirectory(String leaf) throws IOException {
        delete(leaf, WindowsFileHandle.DIRECTORY);
    }

    /** 将源普通文件的 DACL 复制至已创建目标；两端均固定句柄，权限不足必须保留失败。 */
    public void copyAccess(String leaf, WindowsWorkspaceDirectory target, String targetLeaf) throws IOException {
        try (WindowsFileHandle source = handle.child(
                        leaf, WindowsFileHandle.READ_CONTROL, WindowsFileHandle.FILE, WindowsFileHandle.OPEN);
                WindowsFileHandle destination = target.handle.child(
                        targetLeaf, WindowsFileHandle.WRITE_DAC, WindowsFileHandle.FILE, WindowsFileHandle.OPEN)) {
            WindowsWorkspaceAcl.copy(source, destination);
        }
    }

    /** 将本实现可写通道的内容及元数据刷入文件系统；不接受其他来源的通道。 */
    public void force(SeekableByteChannel channel) throws IOException {
        if (!(channel instanceof WindowsWorkspaceChannel nativeChannel)) {
            throw new IOException("Windows Workspace requires a native durable file channel");
        }
        nativeChannel.force();
    }

    WindowsFileHandle openLeaseFile() throws IOException {
        return handle.child("acl.lock", 0x12019f, WindowsFileHandle.FILE, 3);
    }

    private void delete(String leaf, int kind) throws IOException {
        try (WindowsFileHandle child = handle.child(leaf, WindowsFileHandle.DELETE, kind, WindowsFileHandle.OPEN);
                Arena arena = Arena.ofConfined()) {
            MemorySegment disposition = arena.allocate(JAVA_BYTE);
            disposition.set(JAVA_BYTE, 0, (byte) 1);
            setInformation(child, disposition, 1, 13, "delete workspace entry", leaf);
        }
    }

    private static void setInformation(
            WindowsFileHandle file,
            MemorySegment information,
            int length,
            int informationClass,
            String operation,
            String leaf)
            throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment status = arena.allocate(16, 8);
            int code = WindowsFileNative.call(
                            WindowsFileNative.backend().setInformation,
                            file.address(),
                            status,
                            information,
                            length,
                            informationClass)
                    .number();
            WindowsFileNative.ntCheck(operation, leaf, code);
        }
    }

    private static void closeAfterFailure(WindowsFileHandle file, Throwable failure) {
        try {
            file.close();
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    /** 幂等释放本目录能力；由该能力打开的子目录及文件通道由各自调用方关闭。 */
    @Override
    public void close() throws IOException {
        handle.close();
    }

    private record OpenMode(boolean read, boolean write, boolean truncate, int disposition) {
        private static OpenMode parse(Set<? extends OpenOption> options) {
            Objects.requireNonNull(options, "options");
            Set<OpenOption> supported = Set.of(
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS);
            if (!supported.containsAll(options)) {
                throw new IllegalArgumentException("Unsupported Windows Workspace file option");
            }
            boolean write = options.contains(StandardOpenOption.WRITE);
            boolean read = !write || options.contains(StandardOpenOption.READ);
            boolean truncate = write && options.contains(StandardOpenOption.TRUNCATE_EXISTING);
            int disposition = WindowsFileHandle.OPEN;
            if (write && options.contains(StandardOpenOption.CREATE_NEW)) {
                disposition = WindowsFileHandle.CREATE;
            } else if (write && options.contains(StandardOpenOption.CREATE)) {
                disposition = 3;
            }
            return new OpenMode(read, write, truncate, disposition);
        }

        private int access() {
            return (read ? 0x120089 : 0) | (write ? 0x120116 : 0);
        }
    }
}
