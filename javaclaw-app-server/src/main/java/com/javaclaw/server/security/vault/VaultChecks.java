package com.javaclaw.server.security.vault;

import java.util.Arrays;
import java.util.Objects;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.PersistenceException;

/** Vault 写入边界的大小、revision 与命令身份校验。 */
final class VaultChecks {
    private static final int MAX_SECRET_BYTES = 4 * 1024 * 1024;

    private VaultChecks() {}

    static byte[] checkedSecret(byte[] secret) {
        byte[] checked = Objects.requireNonNull(secret, "secret").clone();
        if (checked.length == 0 || checked.length > MAX_SECRET_BYTES) {
            Arrays.fill(checked, (byte) 0);
            throw new IllegalArgumentException("Secret size is outside the supported range");
        }
        return checked;
    }

    static void requireRevision(CredentialMetadata metadata, long expectedRevision) {
        if (metadata.revision() != expectedRevision) {
            throw PersistenceException.revisionConflict("CredentialRef revision 已变化");
        }
    }

    static CommandIdentity requireExpectedRevision(CommandIdentity identity, long expectedRevision) {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        if (checked.expectedRevision() != expectedRevision) {
            throw PersistenceException.invalidRequest("Credential 创建 expected revision 必须为 0");
        }
        return checked;
    }
}
