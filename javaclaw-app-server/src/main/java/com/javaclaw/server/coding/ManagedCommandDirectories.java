package com.javaclaw.server.coding;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;

import com.javaclaw.api.TurnId;

/**
 * 仅在服务端独占祖先下创建缓存根和临时根，不在项目可写缓存内执行宿主 mkdir。
 *
 * <p>祖先从未授予项目写入权，最终根由三平台沙箱禁止删除/替换；这两个 OS 不变量消除检查后被项目换掉祖先的竞态。 不遍历缓存子树、不自动修复重解析点，也不以宿主递归清理运行中的项目临时文件。
 */
final class ManagedCommandDirectories {
    private final Path dataRoot;

    ManagedCommandDirectories(Path dataRoot) {
        this.dataRoot =
                Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
    }

    Prepared prepare(Path cache, TurnId turnId) throws IOException {
        Path requested = cache.toAbsolutePath().normalize();
        if (!requested.startsWith(dataRoot.resolve("coding/caches"))) {
            throw new SecurityException("缓存不在固定管理目录");
        }
        Path canonical = dataRoot.toRealPath();
        Path safeCache = create(canonical, dataRoot.relativize(requested));
        Path temporary = create(canonical, Path.of("coding", "command-tmp", turnId.toString()));
        return new Prepared(safeCache, temporary);
    }

    private static Path create(Path root, Path relative) throws IOException {
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            try {
                if (current.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.createDirectory(
                            current,
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    // Windows 从已经校验的 data-v6 私有 ACL 继承，进程的临时 SID 授权只作用于最终根。
                    Files.createDirectory(current);
                }
            } catch (FileAlreadyExistsException existing) {
                // 只接受普通目录，不能把“已存在”解释为可跟随链接。
            }
            requirePlain(current);
        }
        return current;
    }

    private static void requirePlain(Path path) throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()
                || attributes.isSymbolicLink()
                || attributes.isOther()
                || !path.toRealPath().equals(path)) {
            throw new SecurityException("缓存或临时目录包含链接、重解析点或路径别名");
        }
    }

    record Prepared(Path cache, Path temporary) {}
}
