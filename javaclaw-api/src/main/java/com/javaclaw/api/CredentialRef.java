package com.javaclaw.api;

/**
 * 指向 Secret Vault 中凭据的不可变引用。
 *
 * <p>该类型只携带稳定 opaque 标识，绝不携带、显示或导出 Secret 值。
 *
 * @param namespace 凭据用途命名空间，例如 {@code provider}
 * @param id Vault 分配的 opaque 标识
 */
public record CredentialRef(String namespace, String id) {
    /** 校验命名空间和 opaque 标识。 */
    public CredentialRef {
        namespace = Preconditions.identifier(namespace, "namespace");
        id = Preconditions.identifier(id, "id");
    }
}
