package com.javaclaw.server.security.vault;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.server.persistence.CommandIdentity;

/**
 * 非 Provider 领域的 Vault 原子复合写入端口，复用现有密文、幂等和事务实现。
 *
 * <p>锁顺序沿用 Vault 变更协调锁、Vault monitor、H2 事务；回调禁止另起 Vault 写入。 网站 Cookie 更新只通知相关引用和普通监听器，不重建无关 Provider。
 */
public final class ScopedCredentialVault {
    private final SecretVaultService vault;
    private final VaultCredentialTransactions transactions;
    private final VaultChangeListeners listeners;

    ScopedCredentialVault(
            SecretVaultService vault, VaultCredentialTransactions transactions, VaultChangeListeners listeners) {
        this.vault = vault;
        this.transactions = transactions;
        this.listeners = listeners;
    }

    /**
     * 加密一个非 Provider Secret 候选，调用方仍负责清零原始字节。
     *
     * @param namespace 精确用途命名空间
     * @param previous 已有凭据的精确元数据；首次写入为空
     * @param secret 私有通道拥有的短生命周期明文
     * @return 必须关闭的纯密文候选
     */
    public Mutation prepare(String namespace, Optional<CredentialMetadata> previous, byte[] secret) {
        requireNamespace(namespace);
        Objects.requireNonNull(previous, "previous");
        synchronized (vault) {
            byte[] key = vault.requireReadyKey();
            try {
                if (previous.isEmpty()) {
                    return new Mutation(transactions.prepareCreate(namespace, secret, key, vault.activeKeyId()));
                }
                CredentialMetadata current = previous.orElseThrow();
                if (!namespace.equals(current.reference().namespace())) {
                    throw new IllegalArgumentException("Secret 命名空间与账号绑定不同");
                }
                return new Mutation(transactions.prepareRotate(
                        current.reference(), current.revision(), secret, key, vault.activeKeyId()));
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        }
    }

    /**
     * 构造永久清除候选。
     *
     * @param current 需要精确匹配的当前凭据
     * @return 必须关闭的清除候选
     */
    public Mutation prepareClear(CredentialMetadata current) {
        requireNamespace(current.reference().namespace());
        synchronized (vault) {
            vault.requireReady();
            return new Mutation(
                    transactions.prepareClear(current.reference(), current.revision(), vault.currentTime()));
        }
    }

    /**
     * 原子提交全部秘密变更、领域状态和脱敏幂等回执。
     *
     * @param identity 完整命令身份
     * @param mutations 已加密或清除候选，允许为空
     * @param resultType 脱敏结果类型
     * @param work 同库领域写入
     * @param <T> 脱敏结果类型
     * @return 提交或幂等恢复的结果
     */
    public <T> T commit(
            CommandIdentity identity, List<Mutation> mutations, Class<T> resultType, AtomicCredentialCommit<T> work) {
        List<Mutation> checked = List.copyOf(mutations);
        Set<CredentialRef> references = checked.stream()
                .map(value -> value.metadata().reference())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return listeners.afterScopedChange(vault, references, () -> {
            if (checked.isEmpty()) {
                vault.requireOpen();
            } else {
                vault.requireReady();
            }
            return transactions.commitAll(
                    identity, checked.stream().map(value -> value.prepared).toList(), resultType, work);
        });
    }

    /**
     * 在解封或消费 Worker 内容前恢复已提交结果。
     *
     * @param identity 完整命令身份
     * @param resultType 脱敏结果类型
     * @param <T> 结果类型
     * @return 已提交结果，没有时为空
     */
    public <T> Optional<T> recover(CommandIdentity identity, Class<T> resultType) {
        synchronized (vault) {
            vault.requireOpen();
            return transactions.recover(identity, resultType);
        }
    }

    /**
     * 注册精确引用变化；空集合表示 Vault 全局锁定、重置或主密钥变化。
     *
     * @param listener 同步撤销依赖的监听器，不得在回调中修改 Vault
     */
    public void onChange(Consumer<Set<CredentialRef>> listener) {
        listeners.addScoped(listener);
    }

    private static void requireNamespace(String namespace) {
        new CredentialRef(namespace, "validation");
        if ("provider".equals(namespace)) {
            throw new IllegalArgumentException("Provider 必须使用其已有复合凭据端口");
        }
    }

    /** 只持有密文的短生命周期候选；不允许重复提交。 */
    public static final class Mutation implements AutoCloseable {
        private final PreparedProviderCredentialMutation prepared;

        private Mutation(PreparedProviderCredentialMutation prepared) {
            this.prepared = prepared;
        }

        /** @return 凭据的脱敏元数据 */
        public CredentialMetadata metadata() {
            return prepared.metadata();
        }

        /** 清理候选密文。 */
        @Override
        public void close() {
            prepared.close();
        }
    }
}
