package com.javaclaw.platform.data;

import com.javaclaw.util.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * JavaClaw 4 数据根目录及其格式边界。
 *
 * <p>解析本身不修改文件系统；{@link #prepare()} 必须在数据库、JavaFX 和其他
 * 后台服务启动前调用。空目录会写入格式标记，非空目录只有在标记精确匹配时
 * 才会被接受，因此 4.0 不会误读或覆盖旧版数据。</p>
 *
 * <p>该值对象不可变且线程安全。目录初始化是幂等的；失败不会删除或迁移任何
 * 既有内容。</p>
 */
public record DataRoot(Path path) {

    public static final String DATA_DIR_PROPERTY = "javaclaw.data.dir";
    public static final String FORMAT_FILE = ".javaclaw-format";
    public static final String FORMAT_VERSION = "4";
    public DataRoot {
        path = path.toAbsolutePath().normalize();
    }

    /**
     * 从可信启动位置解析应用数据目录。旧数据目录参数只能指向此精确目录。
     */
    public static DataRoot resolve() {
        ApplicationHome home = ApplicationHome.resolve();
        Path expected = home.dataDirectory();
        String configured = System.getProperty(DATA_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()
                && !Path.of(configured).toAbsolutePath().normalize().equals(expected)) {
            throw new IllegalStateException("外部数据目录参数已停用；JavaClaw 数据只能位于 " + expected);
        }
        try {
            home.requireManaged(expected);
        } catch (IOException failure) {
            throw new IllegalStateException("JavaClaw 数据目录无效", failure);
        }
        return new DataRoot(expected);
    }

    /**
     * 验证或初始化数据目录。
     *
     * @return 当前实例，便于启动链继续传递同一个已验证值
     * @throws IOException 目录不可访问，或非空目录缺少正确的 4.0 格式标记
     */
    public DataRoot prepare() throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("JavaClaw 数据目录不能是符号链接: " + path);
        }
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new IOException("JavaClaw 数据目录不是文件夹: " + path);
        }
        Files.createDirectories(path);

        Path marker = path.resolve(FORMAT_FILE);
        if (Files.isSymbolicLink(marker)) {
            throw new IOException("JavaClaw 数据标记不能是符号链接: " + marker);
        }
        if (isEmpty(path) || containsOnlyInstanceLock(path)) {
            AtomicFileWriter.writeString(marker, FORMAT_VERSION);
            return this;
        }

        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw incompatible("缺少 " + FORMAT_FILE + " 标记");
        }
        String actual = Files.readString(marker).strip();
        if (!FORMAT_VERSION.equals(actual)) {
            throw incompatible("格式版本为 " + actual + "，需要 " + FORMAT_VERSION);
        }
        return this;
    }

    /** Check a managed child path, including every existing component for symbolic links. */
    public Path requireManaged(Path candidate) throws IOException {
        Path checked = candidate.toAbsolutePath().normalize();
        if (!checked.startsWith(path)) {
            throw new IOException("JavaClaw 数据目录外路径被拒绝: " + checked);
        }
        Path current = path;
        if (Files.isSymbolicLink(current)) {
            throw new IOException("JavaClaw 数据目录不能是符号链接: " + current);
        }
        for (Path segment : path.relativize(checked)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("JavaClaw 数据目录内存在符号链接: " + current);
            }
        }
        return checked;
    }

    /** Create a managed directory and reject a link introduced during creation. */
    public Path requireDirectory(Path directory) throws IOException {
        Path checked = requireManaged(directory);
        Files.createDirectories(checked);
        requireManaged(checked);
        if (!Files.isDirectory(checked, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("JavaClaw 数据路径不是文件夹: " + checked);
        }
        return checked;
    }

    private boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private boolean containsOnlyInstanceLock(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.allMatch(entry -> entry.getFileName().toString()
                    .equals(ApplicationUpgradeGuard.DATA_LOCK)
                    && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS));
        }
    }

    private IOException incompatible(String reason) {
        return new IOException("拒绝使用不兼容的数据目录 " + path + "：" + reason
                + "。请使用空目录；旧数据格式不会迁移");
    }
}
