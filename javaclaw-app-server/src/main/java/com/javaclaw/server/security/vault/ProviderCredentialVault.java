package com.javaclaw.server.security.vault;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.PersistenceException;
import com.javaclaw.server.persistence.ProviderCredentialMutationPort;

/** Provider 配置复合命令专用的候选加密、预构造可见性与原子提交边界。 */
public final class ProviderCredentialVault implements ProviderCredentialMutationPort {
    private final SecretVaultService vault;
    private final VaultCredentialTransactions transactions;
    private final ThreadLocal<PreparedProviderCredentialMutation> prepared = new ThreadLocal<>();

    ProviderCredentialVault(SecretVaultService vault, VaultCredentialTransactions transactions) {
        this.vault = Objects.requireNonNull(vault, "vault");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /**
     * 把 Provider Secret 加密为只含密文的候选变更。
     *
     * @param reference 已绑定引用；首次绑定时为空
     * @param expectedRevision 首次绑定为 0，轮换时为当前凭据版本
     * @param secret 解封后的临时明文字节；调用方仍须立即清零原数组
     * @return 必须关闭的密文候选
     */
    @Override
    public PreparedProviderCredentialMutation prepare(
            Optional<CredentialRef> reference, long expectedRevision, byte[] secret) {
        Optional<CredentialRef> checked = Objects.requireNonNull(reference, "reference");
        synchronized (vault) {
            byte[] key = vault.requireReadyKey();
            try {
                if (checked.isEmpty()) {
                    requireNewCredential(expectedRevision);
                    return transactions.prepareCreate("provider", secret, key, vault.activeKeyId());
                }
                return transactions.prepareRotate(
                        requireProviderReference(checked.orElseThrow()),
                        expectedRevision,
                        secret,
                        key,
                        vault.activeKeyId());
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        }
    }

    /**
     * 创建 Provider 凭据清除候选；实际删除只会发生在复合事务内。
     *
     * @param reference Provider Vault 引用
     * @param expectedRevision 当前凭据版本
     * @return 必须关闭的清除候选
     */
    @Override
    public PreparedProviderCredentialMutation prepareClear(CredentialRef reference, long expectedRevision) {
        synchronized (vault) {
            vault.requireReady();
            return transactions.prepareClear(
                    requireProviderReference(reference), expectedRevision, vault.currentTime());
        }
    }

    /**
     * 仅在当前线程和回调期间让候选密文可供 Adapter 预构造解析。
     *
     * <p>候选对象不含明文；每次解析都在 Vault 锁内解密并在 callback 返回后清零。嵌套候选会被拒绝。
     *
     * @param candidate 候选密文
     * @param work 需要预构造 Adapter 的工作
     * @param <T> 结果类型
     * @return 工作结果
     */
    @Override
    public <T> T expose(ProviderCredentialMutationPort.Prepared candidate, Supplier<T> work) {
        PreparedProviderCredentialMutation checked = requirePrepared(candidate);
        checked.metadata();
        if (prepared.get() != null) {
            throw new IllegalStateException("Provider credential preparation must not be nested");
        }
        prepared.set(checked);
        try {
            return Objects.requireNonNull(work, "work").get();
        } finally {
            prepared.remove();
        }
    }

    /**
     * 在一个 H2 事务中提交候选 Vault 行、关联业务写入和幂等回执。
     *
     * @param identity 复合命令身份
     * @param candidate 已完成 Adapter 预构造的候选
     * @param resultType 脱敏结果类型
     * @param work 同库业务写入
     * @param <T> 脱敏结果类型
     * @return 已提交或恢复的结果
     */
    @Override
    public <T> T commit(
            CommandIdentity identity,
            ProviderCredentialMutationPort.Prepared candidate,
            Class<T> resultType,
            ProviderCredentialMutationPort.TransactionWork<T> work) {
        synchronized (vault) {
            vault.requireReady();
            return transactions.commit(identity, requirePrepared(candidate), resultType, work::commit);
        }
    }

    /**
     * 在解封前恢复 Provider 复合命令结果。
     *
     * @param identity 完整命令身份
     * @param resultType 脱敏结果类型
     * @param <T> 结果类型
     * @return 已提交结果
     */
    @Override
    public <T> Optional<T> recover(CommandIdentity identity, Class<T> resultType) {
        synchronized (vault) {
            vault.requireOpen();
            return transactions.recover(identity, resultType);
        }
    }

    PreparedProviderCredentialMutation current(CredentialRef reference) {
        PreparedProviderCredentialMutation current = prepared.get();
        return current != null && current.matches(reference) ? current : null;
    }

    private static PreparedProviderCredentialMutation requirePrepared(ProviderCredentialMutationPort.Prepared value) {
        if (value instanceof PreparedProviderCredentialMutation preparedValue) {
            return preparedValue;
        }
        throw new IllegalArgumentException("Provider credential candidate belongs to another Vault");
    }

    private static void requireNewCredential(long expectedRevision) {
        if (expectedRevision != 0) {
            throw PersistenceException.revisionConflict("未绑定凭据的 expected revision 必须为 0");
        }
    }

    private static CredentialRef requireProviderReference(CredentialRef reference) {
        CredentialRef checked = Objects.requireNonNull(reference, "reference");
        if (!"provider".equals(checked.namespace())) {
            throw new IllegalArgumentException("Provider credential namespace must be provider");
        }
        return checked;
    }
}
