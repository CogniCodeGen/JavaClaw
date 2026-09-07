package com.javaclaw.nativehost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

/** 在任何 H2、Secret 或附件访问前验证 data-v6 根的所有权与访问边界。 */
public final class ManagedRuntimeDirectory {
    private static final Set<PosixFilePermission> PRIVATE = PosixFilePermissions.fromString("rwx------");

    private ManagedRuntimeDirectory() {}

    /**
     * 在数据库、日志或托盘写入前创建并校验独占运行目录。
     *
     * @param root 名称必须为 data-v6 的规范绝对目录
     * @throws IOException 所有者、权限、目录类型或写入探针不满足要求
     */
    public static void prepare(Path root) throws IOException {
        requireCurrentDataRoot(root);
        requireSafeExistingAncestor(root);
        boolean posix = root.getFileSystem().supportedFileAttributeViews().contains("posix");
        var lookup = root.getFileSystem().getUserPrincipalLookupService();
        UserPrincipal currentUser = lookup.lookupPrincipalByName(System.getProperty("user.name"));
        boolean created = createIfMissing(root, posix, currentUser);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("data-v6 必须是普通目录，不能是符号链接");
        }
        if (!Files.getOwner(root, LinkOption.NOFOLLOW_LINKS).equals(currentUser)) {
            throw new IOException("data-v6 必须属于当前运行用户");
        }
        if (posix && !PRIVATE.containsAll(Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("data-v6 不得允许其他用户访问，请将目录权限设为 700");
        }
        if (!posix) {
            AclFileAttributeView acl =
                    Files.getFileAttributeView(root, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (created) {
                RuntimeDirectoryAcl.initialize(acl, currentUser);
            }
            RuntimeDirectoryAcl.validate(acl, currentUser, lookup);
        }
        if (!Files.isWritable(root)) {
            throw new IOException("data-v6 不可写");
        }
        Path probe = Files.createTempFile(root, ".write-probe-", ".tmp");
        Files.delete(probe);
    }

    private static boolean createIfMissing(Path root, boolean posix, UserPrincipal owner) throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        Files.createDirectories(root.getParent());
        if (posix) {
            Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PRIVATE));
        } else {
            try {
                Files.createDirectory(root, RuntimeDirectoryAcl.initialAttribute(owner));
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("data-v6 文件系统不支持创建受保护的初始 ACL", unsupported);
            }
        }
        return true;
    }

    private static void requireCurrentDataRoot(Path root) throws IOException {
        if (!root.isAbsolute()
                || root.getFileName() == null
                || !root.getFileName().toString().equals("data-v6")) {
            throw new IOException("运行数据根必须是绝对 data-v6 目录");
        }
        requireNoLegacySegment(root);
    }

    private static void requireSafeExistingAncestor(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new IOException("data-v6 必须是普通目录，不能是符号链接");
        }
        Path ancestor = root;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new IOException("无法确认 data-v6 的已存在父目录");
        }
        // 创建任何目录前只解析祖先元数据，允许系统正常别名，但不能经父 symlink 写入历史数据目录。
        requireNoLegacySegment(ancestor.toRealPath());
    }

    private static void requireNoLegacySegment(Path path) throws IOException {
        for (Path segment : path) {
            if (segment.toString().equals("data-v5")) {
                throw new IOException("运行数据根不得包含历史 data-v5 目录");
            }
        }
    }
}
