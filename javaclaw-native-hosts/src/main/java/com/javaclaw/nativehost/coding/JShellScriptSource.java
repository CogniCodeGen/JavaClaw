package com.javaclaw.nativehost.coding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 从内置发行资源发布固定 Java 21 Worker 源码；主 runtime 不加载 JShell 类或编译器。
 *
 * <p>管理祖先由 App Server 独占且从不授予项目写入。沙箱只读取最终摘要目录，因此脚本不能替换源文件或其祖先； 发布后版本不自动删除，避免运行中的 Java 源启动器失去代码。同步发布覆盖同进程调用，跨进程所有权沿用
 * data-v6 单 owner。
 */
public final class JShellScriptSource {
    private static final String RESOURCE = "/coding/jshell/JShellScriptWorker.java";
    private static final String NAME = "JShellScriptWorker.java";
    private static final int MAXIMUM_RESOURCE_BYTES = 128 * 1024;

    private JShellScriptSource() {}

    /**
     * 校验内置源码并在私有摘要目录原子发布；已有源码必须与发行内容完全一致。
     *
     * @param dataRoot 已由应用验证并独占的绝对 data-v6 根
     * @return 固定源码路径与完整 SHA-256，不授予目录权限
     * @throws IOException 资源、管理目录、原子发布或完整性校验失败
     */
    public static synchronized Source materialize(Path dataRoot) throws IOException {
        if (!dataRoot.isAbsolute()) {
            throw new IllegalArgumentException("JShell managed data root must be absolute");
        }
        byte[] source = source();
        String digest = digest(source);
        Path directory = dataRoot.toRealPath();
        requireDirectory(directory);
        for (String segment : new String[] {"coding", "runtime", "jshell", digest}) {
            directory = directory.resolve(segment);
            createDirectory(directory);
        }
        Path target = directory.resolve(NAME);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            publish(directory, target, source);
        }
        requireSource(target, source, digest);
        return new Source(target, digest);
    }

    private static byte[] source() throws IOException {
        try (InputStream input = JShellScriptSource.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("Packaged Java 21 JShell source is unavailable");
            }
            byte[] bytes = input.readNBytes(MAXIMUM_RESOURCE_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAXIMUM_RESOURCE_BYTES) {
                throw new IOException("Packaged JShell source exceeds its resource limit");
            }
            return bytes;
        }
    }

    private static void createDirectory(Path directory) throws IOException {
        try {
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createDirectory(
                        directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectory(directory);
            }
        } catch (FileAlreadyExistsException existing) {
            // 只接收既有普通目录，绝不追随项目或外部进程留下的链接。
        }
        requireDirectory(directory);
    }

    private static void requireDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || !directory.toRealPath().equals(directory)) {
            throw new IOException("JShell managed source directory is not a plain canonical directory");
        }
    }

    private static void publish(Path directory, Path target, byte[] source) throws IOException {
        Path staging = Files.createTempFile(directory, ".source-", ".tmp");
        try {
            Files.write(staging, source, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (staging.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("r--------"));
            } else {
                Files.setAttribute(staging, "dos:readonly", true, LinkOption.NOFOLLOW_LINKS);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                if (!staging.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.setAttribute(staging, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
                }
                Files.delete(staging);
            }
        }
    }

    private static void requireSource(Path target, byte[] source, String expected) throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || attributes.size() != source.length
                || !target.toRealPath().equals(target)
                || !digest(Files.readAllBytes(target)).equals(expected)) {
            throw new IOException("JShell managed source does not match the packaged digest");
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    /**
     * 已验证的固定源码，不包含用户脚本。
     *
     * @param path 不可空的规范绝对 Java 源文件路径
     * @param sha256 不可空的完整源码摘要
     */
    public record Source(Path path, String sha256) {}
}
