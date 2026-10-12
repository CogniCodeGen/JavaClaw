package com.javaclaw.platform.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 文件内容的原子替换存储。
 *
 * <p>写入先落在目标同目录的唯一临时文件，再原子替换；文件系统不支持原子移动时回退到
 * 普通覆盖移动。失败保留旧目标内容，临时文件按尽力语义清理。实例无状态且线程安全。</p>
 */
public final class AtomicContentStore {

    public void writeString(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public void write(Path target, byte[] content) throws IOException {
        java.util.Objects.requireNonNull(target, "target");
        java.util.Objects.requireNonNull(content, "content");
        Path absoluteTarget = target.toAbsolutePath().normalize();
        Path directory = absoluteTarget.getParent();
        if (directory == null) {
            throw new IOException("目标文件缺少父目录: " + target);
        }
        Files.createDirectories(directory);
        String prefix = absoluteTarget.getFileName() + ".";
        if (prefix.length() < 3) {
            prefix = "content.";
        }
        Path temporary = Files.createTempFile(directory, prefix, ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, absoluteTarget,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absoluteTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public String readString(Path source) throws IOException {
        return Files.readString(source.toAbsolutePath().normalize(), StandardCharsets.UTF_8);
    }

    public byte[] read(Path source) throws IOException {
        return Files.readAllBytes(source.toAbsolutePath().normalize());
    }
}
