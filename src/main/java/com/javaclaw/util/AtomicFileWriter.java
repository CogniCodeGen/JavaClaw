package com.javaclaw.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 原子文件写入工具：先写同目录临时文件，再原子 rename 覆盖目标文件。
 *
 * <p>解决直接 {@code writeValue(file, ...)} 覆盖写的完整性问题：进程被杀 / 磁盘满时
 * 半截内容只会留在 {@code *.tmp} 残留文件里，目标文件要么是旧的完整版本、要么是新的完整版本，
 * 绝不会出现损坏的半截 JSON 导致下次启动加载失败数据全丢。</p>
 *
 * <p>失败时上抛 {@link IOException}，由调用方按各自既有语义处理（多为记日志告警继续）。</p>
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    /**
     * 以 Jackson 序列化对象并原子写入目标文件。
     *
     * @param writer 序列化器（可传 {@code mapper.writer()} 或 {@code mapper.writerWithDefaultPrettyPrinter()}）
     * @param target 目标文件
     * @param value  待序列化对象
     */
    public static void writeJson(ObjectWriter writer, File target, Object value) throws IOException {
        writeString(target.toPath(), writer.writeValueAsString(value));
    }

    /** 以 ObjectMapper 默认格式序列化并原子写入。 */
    public static void writeJson(ObjectMapper mapper, File target, Object value) throws IOException {
        writeJson(mapper.writer(), target, value);
    }

    /**
     * 原子写入字符串内容（UTF-8）：写 {@code target.tmp} → 原子 move 覆盖 {@code target}；
     * 文件系统不支持原子 move 时回退普通 REPLACE_EXISTING move。
     */
    public static void writeString(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
