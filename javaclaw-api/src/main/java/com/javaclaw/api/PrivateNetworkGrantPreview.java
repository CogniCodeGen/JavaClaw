package com.javaclaw.api;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 私网授权在人工确认前的规范化预览。
 *
 * @param workspaceId 所属 Workspace
 * @param purpose 受控用途
 * @param origin 规范化精确 HTTPS Origin
 * @param dnsAddresses 规范化数字地址集合
 * @param expiresAt 到期时间
 * @param confirmationDigest 以上内容的 SHA-256 确认摘要
 */
public record PrivateNetworkGrantPreview(
        WorkspaceId workspaceId,
        PrivateNetworkPurpose purpose,
        URI origin,
        Set<String> dnsAddresses,
        Instant expiresAt,
        String confirmationDigest) {
    /** 复制集合并校验预览内容。 */
    public PrivateNetworkGrantPreview {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(purpose, "purpose");
        origin = PrivateNetworkGrant.normalizeOrigin(origin);
        dnsAddresses = Set.copyOf(dnsAddresses);
        if (dnsAddresses.isEmpty()) {
            throw new IllegalArgumentException("dnsAddresses must not be empty");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
        confirmationDigest = Preconditions.digest(confirmationDigest, "confirmationDigest");
    }
}
