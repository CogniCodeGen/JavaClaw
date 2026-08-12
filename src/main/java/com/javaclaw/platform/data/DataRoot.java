package com.javaclaw.platform.data;

import com.javaclaw.util.AtomicFileWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JavaClaw 3 数据根目录及其格式边界。
 *
 * <p>解析本身不修改文件系统；{@link #prepare()} 必须在数据库、JavaFX 和其他
 * 后台服务启动前调用。空目录会写入格式标记，非空目录只有在标记精确匹配时
 * 才会被接受，因此 3.0 不会误读或覆盖旧版数据。</p>
 *
 * <p>该值对象不可变且线程安全。目录初始化是幂等的；失败不会删除或迁移任何
 * 既有内容。</p>
 */
public record DataRoot(Path path) {

    public static final String DATA_DIR_PROPERTY = "javaclaw.data.dir";
    public static final String FORMAT_FILE = ".javaclaw-format";
    public static final String FORMAT_VERSION = "3";
    private static final String DEFAULT_DIRECTORY = "data";

    public DataRoot {
        path = path.toAbsolutePath().normalize();
    }

    /**
     * 从系统属性解析数据根目录，未显式配置时使用 {@code {user.dir}/data}。
     */
    public static DataRoot resolve() {
        String configured = System.getProperty(DATA_DIR_PROPERTY);
        Path resolved = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir"), DEFAULT_DIRECTORY)
                : Path.of(configured);
        return new DataRoot(resolved);
    }

    /**
     * 验证或初始化数据目录。
     *
     * @return 当前实例，便于启动链继续传递同一个已验证值
     * @throws IOException 目录不可访问，或非空目录缺少正确的 3.0 格式标记
     */
    public DataRoot prepare() throws IOException {
        if (Files.exists(path) && !Files.isDirectory(path)) {
            throw new IOException("JavaClaw 数据目录不是文件夹: " + path);
        }
        Files.createDirectories(path);

        Path marker = path.resolve(FORMAT_FILE);
        if (isEmpty(path)) {
            AtomicFileWriter.writeString(marker, FORMAT_VERSION);
            return this;
        }

        if (!Files.isRegularFile(marker)) {
            throw incompatible("缺少 " + FORMAT_FILE + " 标记");
        }
        String actual = Files.readString(marker).strip();
        if (!FORMAT_VERSION.equals(actual)) {
            throw incompatible("格式版本为 " + actual + "，需要 " + FORMAT_VERSION);
        }
        return this;
    }

    private boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private IOException incompatible(String reason) {
        return new IOException("拒绝使用不兼容的数据目录 " + path + "：" + reason
                + "。请使用空目录或显式设置 -D" + DATA_DIR_PROPERTY + "=<目录>");
    }
}
