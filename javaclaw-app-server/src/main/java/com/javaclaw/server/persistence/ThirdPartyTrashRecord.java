package com.javaclaw.server.persistence;

import java.util.Objects;

import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.protocol.BundleRpcContracts;

/**
 * 可恢复 Trash 的持久化内容与公开元数据。
 *
 * @param entry 可公开展示的 Trash 条目
 * @param descriptor 被移除 Bundle 描述
 * @param permissions 被确认权限
 */
public record ThirdPartyTrashRecord(
        BundleRpcContracts.TrashEntry entry,
        ExtensionDescriptor descriptor,
        BundleRpcContracts.PermissionReview permissions) {
    /** 校验 Trash 内容。 */
    public ThirdPartyTrashRecord {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(permissions, "permissions");
        if (!entry.extensionId().equals(descriptor.id().value()) || entry.revision() != descriptor.revision()) {
            throw new IllegalArgumentException("Trash entry differs from descriptor");
        }
    }
}
