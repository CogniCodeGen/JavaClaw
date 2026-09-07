package com.javaclaw.nativehost;

import java.io.IOException;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 通过 JDK ACL 视图保护非 POSIX 数据目录；主体来自系统 lookup，不能按显示名称判断信任。
 *
 * <p>新目录先携带 owner-only ACL 创建，再回读验证；已有目录仅验证，不修复或放宽权限。危险的继承 ALLOW 同样拒绝， 不尝试用其他 DENY 推断复杂组成员和 ACL 顺序。无法取得可靠 ACL 时启动失败。
 */
final class RuntimeDirectoryAcl {
    private static final Set<AclEntryPermission> MUTATING = EnumSet.of(
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.DELETE,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER);

    private RuntimeDirectoryAcl() {}

    static FileAttribute<List<AclEntry>> initialAttribute(UserPrincipal owner) {
        List<AclEntry> entries = ownerOnly(owner);
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return entries;
            }
        };
    }

    static void initialize(AclFileAttributeView view, UserPrincipal owner) throws IOException {
        requireOwner(view, owner);
        List<AclEntry> expected = ownerOnly(owner);
        view.setAcl(expected);
        List<AclEntry> actual = view.getAcl();
        if (actual.isEmpty() || !actual.contains(expected.getFirst())) {
            throw new IOException("data-v6 未能保留当前所有者的完整继承 ACL");
        }
        for (AclEntry entry : actual) {
            if (entry.type() == AclEntryType.ALLOW && !owner.equals(entry.principal())) {
                throw new IOException("data-v6 新目录仍继承其他主体的访问权限");
            }
        }
    }

    static void validate(AclFileAttributeView view, UserPrincipal owner, UserPrincipalLookupService lookup)
            throws IOException {
        requireOwner(view, owner);
        Set<UserPrincipal> trusted = trusted(owner, lookup);
        List<AclEntry> entries = view.getAcl();
        if (entries.isEmpty()) {
            throw new IOException("data-v6 ACL 为空，不能确认其访问边界");
        }
        for (AclEntry entry : entries) {
            if (entry.type() == AclEntryType.ALLOW
                    && !trusted.contains(entry.principal())
                    && entry.permissions().stream().anyMatch(MUTATING::contains)) {
                throw new IOException("data-v6 ACL 向非受信主体授予写入、删除或权限管理能力");
            }
        }
    }

    private static void requireOwner(AclFileAttributeView view, UserPrincipal owner) throws IOException {
        if (view == null) {
            throw new IOException("data-v6 文件系统不提供可验证的 ACL");
        }
        if (!owner.equals(view.getOwner())) {
            throw new IOException("data-v6 ACL 所有者必须是当前运行用户");
        }
    }

    private static List<AclEntry> ownerOnly(UserPrincipal owner) {
        return List.of(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build());
    }

    private static Set<UserPrincipal> trusted(UserPrincipal owner, UserPrincipalLookupService lookup)
            throws IOException {
        Set<UserPrincipal> trusted = new HashSet<>();
        trusted.add(owner);
        try {
            trusted.add(lookup.lookupPrincipalByName("NT AUTHORITY\\SYSTEM"));
        } catch (UserPrincipalNotFoundException unavailable) {
            // 无法解析受信系统账户时不按名称猜测；其 ACL 条目会按普通主体校验。
        }
        try {
            trusted.add(lookup.lookupPrincipalByGroupName("BUILTIN\\Administrators"));
        } catch (UserPrincipalNotFoundException unavailable) {
            // 本地管理员组不可解析时保持 owner-only 信任，不使用同名域组。
        }
        return Set.copyOf(trusted);
    }
}
