package com.javaclaw.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * 平台确认的目录变化；目录没有文件正文摘要，不可投影成文件预览。
 *
 * @param relativePath executionRoot 内非根相对路径，不可空
 * @param operation create 或 delete，不可空
 */
public record DirectoryChange(Path relativePath, String operation) implements ItemPayload {
    /** 拒绝根目录、逃逸路径和未知操作。 */
    public DirectoryChange {
        relativePath = Objects.requireNonNull(relativePath, "relativePath").normalize();
        if (relativePath.isAbsolute()
                || relativePath.startsWith("..")
                || relativePath.toString().isEmpty()) {
            throw new IllegalArgumentException("directory change must stay below executionRoot");
        }
        if (!Set.of("create", "delete").contains(operation)) {
            throw new IllegalArgumentException("unknown directory change operation");
        }
    }
}
