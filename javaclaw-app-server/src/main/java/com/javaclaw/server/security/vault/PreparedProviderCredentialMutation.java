package com.javaclaw.server.security.vault;

import java.util.Objects;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.server.persistence.ProviderCredentialMutationPort;

/**
 * 仅含 Vault 密文的 Provider 凭据候选变更。
 *
 * <p>对象不保存 Secret 明文，可在 Adapter 预构造和 H2 提交之间短暂持有。调用方必须关闭；提交或关闭后不能复用。
 */
public final class PreparedProviderCredentialMutation implements ProviderCredentialMutationPort.Prepared {
    private final Action action;
    private final CredentialMetadata metadata;
    private final String keyId;
    private EncryptedSecret encrypted;
    private boolean committed;
    private boolean closed;

    PreparedProviderCredentialMutation(
            Action action, CredentialMetadata metadata, String keyId, EncryptedSecret encrypted) {
        this.action = Objects.requireNonNull(action, "action");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.keyId = keyId;
        this.encrypted = encrypted;
        if (action == Action.CLEAR ? keyId != null || encrypted != null : keyId == null || encrypted == null) {
            throw new IllegalArgumentException("prepared credential mutation shape is invalid");
        }
    }

    /** @return 将被创建、轮换或清除的凭据元数据 */
    public CredentialMetadata metadata() {
        requireOpen();
        return metadata;
    }

    boolean matches(CredentialRef reference) {
        return !closed && action != Action.CLEAR && metadata.reference().equals(reference);
    }

    Action action() {
        requireOpen();
        return action;
    }

    String keyId() {
        requireOpen();
        return keyId;
    }

    EncryptedSecret encrypted() {
        requireOpen();
        return encrypted;
    }

    void committed() {
        requireOpen();
        committed = true;
    }

    boolean isCommitted() {
        return committed;
    }

    /** 丢弃候选密文并禁止再次提交或用于 Adapter 预构造。 */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (encrypted != null) {
            encrypted.destroy();
            encrypted = null;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("prepared credential mutation is closed");
        }
    }

    enum Action {
        CREATE,
        ROTATE,
        CLEAR
    }
}
