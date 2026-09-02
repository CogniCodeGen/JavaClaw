package com.javaclaw.api;

import java.nio.file.Path;
import java.util.List;

/**
 * 文件访问边界。
 *
 * @param readRoots 允许读取的规范绝对根目录
 * @param writeRoots 允许写入的规范绝对根目录
 * @param allowDelete 是否允许删除
 * @param followSymbolicLinks 是否允许跟随符号链接
 */
public record FilePermission(
        List<Path> readRoots, List<Path> writeRoots, boolean allowDelete, boolean followSymbolicLinks) {
    /** 复制并规范化路径；空集合表示没有权限。 */
    public FilePermission {
        readRoots = normalize(readRoots);
        writeRoots = normalize(writeRoots);
    }

    private static List<Path> normalize(List<Path> roots) {
        return roots.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
                .toList();
    }
}
