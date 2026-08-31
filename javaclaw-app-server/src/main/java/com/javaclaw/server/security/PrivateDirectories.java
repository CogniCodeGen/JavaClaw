package com.javaclaw.server.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

/** 为应用自有辅助进程缓存建立并验证所有者独占目录；不处理用户选中的普通工作区文件。 */
public final class PrivateDirectories {
    private PrivateDirectories() {}

    /** 创建固定目录并施加 POSIX 0700 或当前所有者独占 ACL；无法确认独占时拒绝继续。 */
    public static Path create(Path requested) throws IOException {
        Path path = requested.toAbsolutePath().normalize();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("private directory is not a real directory");
        }
        Files.createDirectories(path);
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            var permissions = PosixFilePermissions.fromString("rwx------");
            posix.setPermissions(permissions);
            if (!posix.readAttributes().permissions().equals(permissions)) {
                throw new IOException("private directory permissions did not apply");
            }
        } else {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) {
                throw new IOException("owner-exclusive permissions are unsupported");
            }
            var entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(acl.getOwner())
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
                    .build();
            acl.setAcl(List.of(entry));
            if (acl.getAcl().stream()
                    .anyMatch(value -> value.type() == AclEntryType.ALLOW
                            && !value.principal().equals(entry.principal()))) {
                throw new IOException("private directory ACL did not apply");
            }
        }
        return path.toRealPath();
    }
}
