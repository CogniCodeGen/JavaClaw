package com.javaclaw.server.security.vault;

import java.util.Objects;

import com.javaclaw.api.CredentialMetadata;

/**
 * Vault 密文的不可变身份绑定。
 *
 * @param namespace Provider、MCP、Site 或 Browser 等受限命名空间
 * @param reference SecretRef 的稳定随机标识
 * @param revision Secret 版本
 */
record SecretBinding(String namespace, String reference, long revision) {
    SecretBinding {
        namespace = identifier(namespace, "namespace");
        reference = identifier(reference, "reference");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }

    String aad() {
        return "javaclaw-vault\u0000" + namespace + '\u0000' + reference + '\u0000' + revision;
    }

    static SecretBinding from(CredentialMetadata metadata) {
        CredentialMetadata checked = Objects.requireNonNull(metadata, "metadata");
        return new SecretBinding(
                checked.reference().namespace(), checked.reference().id(), checked.revision());
    }

    private static String identifier(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return checked;
    }
}
